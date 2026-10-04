package io.zodia.pgledger;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceSnapshot;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CashMovement;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.SnapshotMovement;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerViolation;
import io.zodia.pgledger.store.PostgresLedgerStore;
import io.zodia.pgledger.store.SqlScripts;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PgLedgerTest {
    /** In-process Postgres (zonky). No Docker. Stress/compose still cover streaming replica. */
    private static EmbeddedPostgres POSTGRES;
    private static final AtomicLong IDS = new AtomicLong();

    @BeforeAll
    static void startPostgres() throws Exception {
        POSTGRES = EmbeddedPostgres.builder().setPort(0).start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (POSTGRES != null) {
            POSTGRES.close();
            POSTGRES = null;
        }
    }

    @Test
    void schemaMigrationIsSafeWhenInstancesStartTogether() throws Exception {
        DataSource writer = dataSource();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            ArrayList<Future<?>> tasks = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                tasks.add(pool.submit(() -> PostgresLedgerStore.migrate(writer)));
            }
            for (int i = 0; i < tasks.size(); i++) {
                tasks.get(i).get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void createListsEveryBalanceTypeAndRejectsDuplicates() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String prefix = id("LIST");
            String available = prefix + "A";
            String locked = prefix + "L";
            String trade = prefix + "T";
            String accountId = prefix + "ACCT";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            ledger.createBalanceType(new CreateBalanceType(locked, "Locked", null));
            BalanceType tradeType = ledger.createBalanceType(new CreateBalanceType(trade, "Trade ahead", null));
            assertEquals(trade, tradeType.code());
            assertFalse(tradeType.allowNegative());
            assertTrue(tradeType.allowPositive());
            BalanceType signed = ledger.createBalanceType(
                    new CreateBalanceType(prefix + "NEG", "Neg", null, true, false));
            assertTrue(signed.allowNegative());
            assertFalse(signed.allowPositive());
        LedgerViolation duplicateType = assertThrows(LedgerViolation.class,
                    () -> ledger.createBalanceType(new CreateBalanceType(available, "dup", null)));
        assertEquals("balance type already exists", duplicateType.getMessage());
        LedgerViolation missingType = assertThrows(LedgerViolation.class,
                    () -> ledger.createAccount(account(accountId, prefix + "M", "USD")));
        assertTrue(missingType.getMessage().contains("balance type not found"));

            Account availableRow = ledger.createAccount(account(accountId, available, "USD"));
            Account lockedRow = ledger.createAccount(account(accountId, locked, "USD"));
            ledger.createAccount(account(accountId, available, "EUR"));
            assertEquals(accountId, availableRow.accountId());
            assertEquals(available, availableRow.balanceType());
            assertEquals(0, BigDecimal.ZERO.compareTo(availableRow.balance()));
            assertEquals(0L, availableRow.version());
            assertFalse(availableRow.id().equals(lockedRow.id()));

            nodes.awaitCatchUp();
            List<Account> rows = ledger.balances(accountId);
        assertEquals(3, rows.size());
            assertEquals(available, rows.get(0).balanceType());
        assertEquals("EUR", rows.get(0).currency());
            assertEquals(available, rows.get(1).balanceType());
        assertEquals("USD", rows.get(1).currency());
            assertEquals(locked, rows.get(2).balanceType());

        LedgerViolation duplicate = assertThrows(LedgerViolation.class,
                    () -> ledger.createAccount(account(accountId, available, "USD")));
        assertEquals("account already exists", duplicate.getMessage());
            assertNull(ledger.balance(prefix + "NONE", available, "USD"));
            List<BalanceType> types = ledger.balanceTypes();
            assertEquals(1, countCode(types, available));
            assertEquals(1, countCode(types, locked));
            assertEquals(1, countCode(types, trade));
        }
    }

    @Test
    void transferMovesOneBalanceRowAndRecordsEntries() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String prefix = id("XFER");
            String available = prefix + "A";
            String locked = prefix + "L";
            String client = prefix + "C";
            String company = prefix + "CO";
            BalanceType availableType = ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            BalanceType lockedType = ledger.createBalanceType(new CreateBalanceType(locked, "Locked", null));
            ledger.createAccount(account(client, available, "USD"));
            ledger.createAccount(account(client, locked, "USD"));
            // The funding house sits below zero, so it uses the BANK class override.
            ledger.createAccount(new CreateAccount(company, available, "USD", company, null, "BANK"));

            Transfer funded = ledger.post(posting(company, available, client, available, "USD", "100"));
            assertEquals(company, funded.fromAccountId());
            assertEquals(client, funded.toAccountId());
            assertEquals("TRANSFER", funded.bizType());
            assertNotNull(funded.requestId());
            Transfer replay = ledger.post(new Posting(
                    company, available, client, available, "USD", new BigDecimal("100"), funded.requestId(), null));
            assertEquals(funded.id(), replay.id());
            LedgerViolation reused = assertThrows(LedgerViolation.class,
                    () -> ledger.post(new Posting(
                            company, available, client, available, "USD", new BigDecimal("101"), funded.requestId(), null)));
            assertTrue(reused.getMessage().contains("request id already used"));
            // Post responses no longer join entries (hot-path read-back cut);
            // verify the two entries through the journal instead.
            assertTrue(journalHas(ledger, funded.id()), "journal must show both entries");

            Transfer held = ledger.post(posting(client, available, client, locked, "USD", "40"));
            assertEquals(availableType.id(), held.fromBalanceType());
            assertEquals(lockedType.id(), held.toBalanceType());
            nodes.awaitCatchUp();
            assertEquals(0, new BigDecimal("60").compareTo(ledger.balance(client, available, "USD").balance()));
            assertEquals(0, new BigDecimal("40").compareTo(ledger.balance(client, locked, "USD").balance()));
            assertEquals(2L, ledger.balance(client, available, "USD").version());
        }
    }

    @Test
    void rejectsInsufficientBalanceCurrencyMismatchAndSameRow() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String prefix = id("REJ");
            String available = prefix + "A";
            String sinkable = prefix + "SINK";
            String client = prefix + "C";
            String sink = prefix + "S";
            String eurSink = prefix + "E";
            String company = prefix + "CO";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            // Sign policy is per type now: this bucket refuses credits entirely.
            ledger.createBalanceType(new CreateBalanceType(sinkable, "Sink", null, true, false));
            ledger.createAccount(account(client, available, "USD"));
            ledger.createAccount(account(client, available, "EUR"));
            ledger.createAccount(account(sink, sinkable, "USD"));
            // The funding house sits below zero, so it uses the BANK class override.
            ledger.createAccount(new CreateAccount(company, available, "USD", company, null, "BANK"));
            // USD-only row on the same type. Crediting it in EUR is a currency mismatch.
            ledger.createAccount(account(eurSink, available, "USD"));
            nodes.awaitCatchUp();
            long journalsBefore = ledger.journals(0, 1).total();
            ledger.post(posting(company, available, client, available, "USD", "10"));

        LedgerViolation poor = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(client, available, company, available, "USD", "20")));
        assertTrue(poor.getMessage().contains("does not allow negative balance"));
            nodes.awaitCatchUp();
            assertEquals(0, new BigDecimal("10").compareTo(ledger.balance(client, available, "USD").balance()));
            assertEquals(journalsBefore + 1L, ledger.journals(0, 1).total());

            // eurSink has no EUR row yet. autoCreate=false on plain postings
            // surfaces the missing row instead of silently creating it.
        LedgerViolation currency = assertThrows(LedgerViolation.class,
                () -> ledger.post(new Posting(
                            client, available, eurSink, available, "EUR", BigDecimal.ONE, id("REQ"), null, null, false)));
            assertTrue(currency.getMessage().contains("Account not found"));
            // Fund the client EUR row first (company is a BANK and may go negative);
            // the company EUR row itself is auto-created here.
            ledger.post(new Posting(
                    company, available, client, available, "EUR", new BigDecimal("5"), id("REQ"), null, null, true));
            // With autoCreate the missing eurSink EUR row is created on the fly;
            // both sides stay in EUR and the transfer commits.
            Transfer eurMove = ledger.post(new Posting(
                    client, available, eurSink, available, "EUR", BigDecimal.ONE, id("REQ"), null, null, true));
            assertEquals(0, new BigDecimal("4").compareTo(ledger.balance(client, available, "EUR").balance()));
            assertEquals(0, BigDecimal.ONE.compareTo(ledger.balance(eurSink, available, "EUR").balance()));
            assertNotNull(eurMove.id());

        LedgerViolation missing = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(prefix + "NOPE", available, company, available, "USD", "1")));
        assertTrue(missing.getMessage().contains("Account not found"));

        LedgerViolation same = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(client, available, client, available, "USD", "1")));
        assertTrue(same.getMessage().contains("Cannot transfer to the same account"));

        LedgerViolation positive = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(company, available, sink, sinkable, "USD", "1")));
        assertTrue(positive.getMessage().contains("does not allow positive balance"));
            assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balance(sink, sinkable, "USD").balance()));

        LedgerViolation amount = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(company, available, client, available, "USD", "0")));
        assertTrue(amount.getMessage().contains("must be positive"));
        LedgerViolation blank = assertThrows(LedgerViolation.class,
                    () -> ledger.createAccount(new CreateAccount("  ", available, "USD", null, null, null)));
        assertEquals("account_id, balance_type, and currency are required", blank.getMessage());
        }
    }

    @Test
    void journalsAreNewestFirst() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String prefix = id("JRN");
            String available = prefix + "A";
            String client = prefix + "C";
            String company = prefix + "CO";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            ledger.createAccount(account(client, available, "USD"));
            // The funding house sits below zero, so it uses the BANK class override.
            ledger.createAccount(new CreateAccount(company, available, "USD", company, null, "BANK"));
            ledger.post(posting(company, available, client, available, "USD", "10"));
            ledger.post(posting(company, available, client, available, "USD", "20"));
            ledger.post(posting(company, available, client, available, "USD", "30"));
            nodes.awaitCatchUp();

        JournalPage first = ledger.journals(0, 2);
            assertTrue(first.total() >= 3L);
            assertEquals(2, first.transfers().size());
        assertTrue(first.hasNext());
            assertNewerFirst(first.transfers().get(0), first.transfers().get(1));

        JournalPage second = ledger.journals(1, 2);
            assertEquals(first.total(), second.total());
            assertFalse(second.transfers().isEmpty());
            assertNewerFirst(first.transfers().get(1), second.transfers().get(0));

            ArrayList<BigDecimal> ours = new ArrayList<>(3);
            for (int page = 0; page < 20 && ours.size() < 3; page++) {
                JournalPage journalPage = ledger.journals(page, 50);
                assertEquals(first.total(), journalPage.total());
                assertTrue(journalPage.transfers().size() <= 50);
                for (int i = 0; i < journalPage.transfers().size(); i++) {
                    Transfer transfer = journalPage.transfers().get(i);
                    if (company.equals(transfer.fromAccountId()) && client.equals(transfer.toAccountId())) {
                        ours.add(transfer.amount());
                        assertEquals(2, transfer.entries().size());
                    }
                }
                if (!journalPage.hasNext()) {
                    break;
                }
            }
            assertEquals(3, ours.size(), "transfers not in the newest journal pages");
            assertEquals(0, new BigDecimal("30").compareTo(ours.get(0)));
            assertEquals(0, new BigDecimal("20").compareTo(ours.get(1)));
            assertEquals(0, new BigDecimal("10").compareTo(ours.get(2)));

            long emptyPage = (first.total() + 1L) / 2L;
            assertTrue(emptyPage <= Integer.MAX_VALUE);
            JournalPage empty = ledger.journals((int) emptyPage, 2);
        assertEquals(0, empty.transfers().size());
            assertEquals(first.total(), empty.total());
            assertFalse(empty.hasNext());
        assertThrows(IllegalArgumentException.class, () -> ledger.journals(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> ledger.journals(0, 201));
        }
    }

    @Test
    void omittedNameDefaultsAndMetadataRoundTrips() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String accountId = id("META");
            String available = accountId + "A";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
        Account created = ledger.createAccount(new CreateAccount(
                    accountId, available, "USD", "  ", Map.of("desk", "fx"), null));
            assertEquals(accountId, created.name());
            // Default policy on a fresh type: negative denied, positive allowed.
            assertFalse(created.allowNegativeBalance());
        assertTrue(created.allowPositiveBalance());
            assertEquals("CLIENT", created.accountClass());
            assertFalse(created.deleted());
        assertEquals("fx", created.metadata().get("desk"));

            Account bank = ledger.createAccount(new CreateAccount(
                    accountId + "B", available, "USD", null, null, "bank"));
            assertEquals("BANK", bank.accountClass());
            assertTrue(bank.allowNegativeBalance());
            assertTrue(bank.allowPositiveBalance());
            LedgerViolation badClass = assertThrows(LedgerViolation.class,
                    () -> ledger.createAccount(new CreateAccount(
                            accountId + "X", available, "USD", null, null, "HOT")));
            assertTrue(badClass.getMessage().contains("account_class"));

            try (Connection conn = dataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         INSERT INTO pgledger_accounts (
                             account_id, balance_type_id, name, currency_id,
                             created_at, updated_at)
                         VALUES (
                             ?,
                             (SELECT id FROM pgledger_balance_types WHERE code = ?),
                             'legacy',
                             (SELECT id FROM pgledger_currencies WHERE code = 'USD'),
                             now(), now())
                         RETURNING (
                             SELECT code FROM pgledger_account_classes
                             WHERE id = pgledger_accounts.account_class_id
                         ), deleted
                         """)) {
                ps.setString(1, accountId + "LEGACY");
                ps.setString(2, available);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CLIENT", rs.getString(1));
                    assertFalse(rs.getBoolean(2));
                }
            }
        }
    }

    @Test
    void depositCreditsClientAndDebitsOneBankShard() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String type = id("DEP") + "A";
            String client = id("DEPC");
            ledger.createBalanceType(new CreateBalanceType(type, "Available", null));
            ledger.createAccount(account(client, type, "USD"));
            BigDecimal amount = new BigDecimal("30");
            Transfer deposit = ledger.deposit(cash(id("DREQ"), client, type, amount));
            assertEquals(client, deposit.toAccountId());
            assertEquals("DEPOSIT", deposit.bizType());
            assertTrue(deposit.fromAccountId().startsWith("BANK-USD-" + type + "-"));
            // Entries verified via journal: post responses are transfer-only.
            assertTrue(journalHas(ledger, deposit.id()), "journal must show both entries");
            assertEquals(shardOf(deposit.fromAccountId()), bankShard(deposit.requestId(), 8));

            nodes.awaitCatchUp();
            List<Account> shards = ledger.bankShards(type, "USD");
            assertEquals(PgLedger.DEFAULT_BANK_POOL_SIZE, shards.size());
            int debited = 0;
            for (int i = 0; i < shards.size(); i++) {
                Account shard = shards.get(i);
                assertEquals("BANK", shard.accountClass());
                assertTrue(shard.allowNegativeBalance());
                assertTrue(shard.allowPositiveBalance());
                if (shard.accountId().equals(deposit.fromAccountId())) {
                    debited++;
                    assertEquals(0, amount.negate().compareTo(shard.balance()));
                    assertTrue(shard.balance().signum() < 0);
                } else {
                    assertEquals(0, BigDecimal.ZERO.compareTo(shard.balance()));
                }
            }
            assertEquals(1, debited);
            assertEquals(0, amount.compareTo(ledger.balance(client, type, "USD").balance()));
            assertEquals(0, amount.negate().compareTo(ledger.bankPosition(type, "USD")));

            Transfer withdrawal = ledger.withdraw(cash(id("WREQ"), client, type, amount));
            assertEquals(client, withdrawal.fromAccountId());
            assertEquals("WITHDRAWAL", withdrawal.bizType());
            assertTrue(withdrawal.toAccountId().startsWith("BANK-USD-" + type + "-"));
            nodes.awaitCatchUp();
            assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balance(client, type, "USD").balance()));
            assertEquals(0, BigDecimal.ZERO.compareTo(ledger.bankPosition(type, "USD")));
            assertEquals(0, BigDecimal.ZERO.compareTo(sum(ledger.bankShards(type, "USD"))));

            LedgerViolation floor = assertThrows(LedgerViolation.class,
                    () -> ledger.withdraw(cash(id("WREQ2"), client, type, BigDecimal.ONE)));
            assertTrue(floor.getMessage().contains("does not allow negative balance"));
            nodes.awaitCatchUp();
            assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balance(client, type, "USD").balance()));
            assertEquals(0, BigDecimal.ZERO.compareTo(ledger.bankPosition(type, "USD")));
        }
    }

    @Test
    void depositsCanHitDifferentShardsAndReplayDoesNotDoublePost() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String type = id("SH") + "A";
            String client = id("SHC");
            ledger.createBalanceType(new CreateBalanceType(type, "Available", null));
            ledger.createAccount(account(client, type, "USD"));
            assertEquals(8, ledger.ensureBankPool(type, "USD", 8));
            LedgerViolation resized = assertThrows(LedgerViolation.class,
                    () -> ledger.ensureBankPool(type, "USD", 4));
            assertTrue(resized.getMessage().contains("bank pool size"));

            String[] ids = requestPair(8, false);
            BigDecimal amount = new BigDecimal("10");
            Transfer first = ledger.deposit(cash(ids[0], client, type, amount));
            Transfer second = ledger.deposit(cash(ids[1], client, type, amount));
            assertFalse(first.fromAccountId().equals(second.fromAccountId()));
            assertFalse(first.id().equals(second.id()));
            nodes.awaitCatchUp();
            assertEquals(2, nonzero(ledger.bankShards(type, "USD")));
            assertEquals(0, amount.add(amount).compareTo(ledger.balance(client, type, "USD").balance()));
            assertEquals(0, amount.add(amount).negate().compareTo(ledger.bankPosition(type, "USD")));

            Transfer replay = ledger.deposit(cash(ids[0], client, type, amount));
            assertEquals(first.id(), replay.id());
            nodes.awaitCatchUp();
            assertEquals(0, amount.add(amount).compareTo(ledger.balance(client, type, "USD").balance()));
            LedgerViolation reused = assertThrows(LedgerViolation.class,
                    () -> ledger.deposit(cash(ids[0], client, type, new BigDecimal("11"))));
            assertTrue(reused.getMessage().contains("request id already used"));
            assertEquals(0, amount.add(amount).compareTo(ledger.balance(client, type, "USD").balance()));

            CyclicBarrier start = new CyclicBarrier(2);
            CashMovement same = cash(ids[0], client, type, amount);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Transfer> left = pool.submit(() -> {
                    start.await();
                    return ledger.deposit(same);
                });
                Future<Transfer> right = pool.submit(() -> {
                    start.await();
                    return ledger.deposit(same);
                });
                assertEquals(first.id(), left.get(30, TimeUnit.SECONDS).id());
                assertEquals(first.id(), right.get(30, TimeUnit.SECONDS).id());
            } finally {
                pool.shutdownNow();
            }
            nodes.awaitCatchUp();
            assertEquals(0, amount.add(amount).compareTo(ledger.balance(client, type, "USD").balance()));
        }
    }

    @Test
    void deletedBankShardIsNotChosenAndDeletedClientRejectsDeposit() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String type = id("DEL") + "A";
            String client = id("DELC");
            ledger.createBalanceType(new CreateBalanceType(type, "Available", null));
            Account clientRow = ledger.createAccount(account(client, type, "USD"));
            assertEquals("CLIENT", clientRow.accountClass());
            assertEquals(2, ledger.ensureBankPool(type, "USD", 2));
            nodes.awaitCatchUp();
            List<Account> before = ledger.bankShards(type, "USD");
            assertEquals(2, before.size());
            Account shard0 = before.get(0);
            Account shard1 = before.get(1);

            ledger.deleteAccount(shard0.accountId(), type, "USD");
            BigDecimal amount = new BigDecimal("7");
            String aimedAtDeleted = requestForShard(2, 0);
            String aimedAtLive = requestForShard(2, 1);
            LedgerViolation blocked = assertThrows(LedgerViolation.class,
                    () -> ledger.deposit(cash(aimedAtDeleted, client, type, amount)));
            assertTrue(blocked.getMessage().contains("deleted"));
            Transfer deposit = ledger.deposit(cash(aimedAtLive, client, type, amount));
            assertEquals(shard1.accountId(), deposit.fromAccountId());
            assertFalse(shard0.accountId().equals(deposit.fromAccountId()));
            nodes.awaitCatchUp();
            List<Account> after = ledger.bankShards(type, "USD");
            assertTrue(after.get(0).deleted());
            assertFalse(after.get(1).deleted());
            assertEquals(0, BigDecimal.ZERO.compareTo(after.get(0).balance()));
            assertEquals(0, amount.negate().compareTo(after.get(1).balance()));
            assertEquals(shard1.accountId(), after.get(1).accountId());

            LedgerViolation named = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(shard0.accountId(), type, client, type, "USD", "1")));
            assertTrue(named.getMessage().contains("deleted"));

            Transfer original = deposit;
            ledger.deleteAccount(client, type, "USD");
            Transfer replay = ledger.deposit(cash(aimedAtLive, client, type, amount));
            assertEquals(original.id(), replay.id());
            LedgerViolation rejected = assertThrows(LedgerViolation.class,
                    () -> ledger.deposit(cash(id("NEW"), client, type, amount)));
            assertTrue(rejected.getMessage().contains("deleted"));
            LedgerViolation posted = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(shard1.accountId(), type, client, type, "USD", "1")));
            assertTrue(posted.getMessage().contains("deleted"));
            nodes.awaitCatchUp();
            Account still = ledger.balance(client, type, "USD");
            assertTrue(still.deleted());
            assertEquals(0, amount.compareTo(still.balance()));
            assertTrue(journalHas(ledger, original.id()));
        }
    }

    @Test
    @Timeout(60)
    void concurrentDepositOnOneShardWaitsAndBalances() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String type = id("WAIT") + "A";
            String client = id("WAITC");
            ledger.createBalanceType(new CreateBalanceType(type, "Available", null));
            ledger.createAccount(account(client, type, "USD"));
            assertEquals(8, ledger.ensureBankPool(type, "USD", 8));
            String requestId = id("HOLD");
            int shard = bankShard(requestId, 8);
            BigDecimal amount = new BigDecimal("4");
            DataSource writer = dataSource();
            try (Connection hold = writer.getConnection()) {
                hold.setAutoCommit(false);
                try (PreparedStatement ps = hold.prepareStatement("""
                        SELECT a.id
                        FROM pgledger_accounts a
                        JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id
                        JOIN pgledger_currencies c ON c.id = a.currency_id
                        WHERE a.account_id = ? AND bt.code = ? AND c.code = ?
                        FOR UPDATE OF a
                        """)) {
                    ps.setString(1, "BANK-USD-" + type + "-" + shard);
                    ps.setString(2, type);
                    ps.setString(3, "USD");
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                    }
                }
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Future<Transfer> posted = pool.submit(() -> ledger.deposit(cash(requestId, client, type, amount)));
                    assertTrue(awaitLock(writer, "pgledger_create_transfer"), "deposit did not wait on the bank shard");
                    assertFalse(posted.isDone());
                    hold.commit();
                    Transfer transfer = posted.get(30, TimeUnit.SECONDS);
                    assertEquals(client, transfer.toAccountId());
                    assertEquals(shard, shardOf(transfer.fromAccountId()));
                } finally {
                    pool.shutdownNow();
                }
            }

            String[] same = requestPairOnShard(8, shard);
            CyclicBarrier start = new CyclicBarrier(2);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Transfer> left = pool.submit(() -> {
                    start.await();
                    return ledger.deposit(cash(same[0], client, type, amount));
                });
                Future<Transfer> right = pool.submit(() -> {
                    start.await();
                    return ledger.deposit(cash(same[1], client, type, amount));
                });
                assertEquals(shard, shardOf(left.get(30, TimeUnit.SECONDS).fromAccountId()));
                assertEquals(shard, shardOf(right.get(30, TimeUnit.SECONDS).fromAccountId()));
            } finally {
                pool.shutdownNow();
            }
            nodes.awaitCatchUp();
            BigDecimal total = amount.multiply(new BigDecimal("3"));
            assertEquals(0, total.compareTo(ledger.balance(client, type, "USD").balance()));
            List<Account> shards = ledger.bankShards(type, "USD");
            assertEquals(0, total.negate().compareTo(shards.get(shard).balance()));
            assertEquals(1, nonzero(shards));
            assertEquals(0, total.negate().compareTo(ledger.bankPosition(type, "USD")));
        }
    }

    @Test
    @Timeout(60)
    void hourlySnapshotsTrackBalancesAndMovements() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String prefix = id("SNP");
            String available = prefix + "A";
            String client = prefix + "C";
            String other = prefix + "O";
            String company = prefix + "CO";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            ledger.createAccount(new CreateAccount(company, available, "USD", company, null, "BANK"));
            ledger.createAccount(account(client, available, "USD"));
            ledger.createAccount(account(other, available, "USD"));

            // Work inside two consecutive full UTC hours.
            Instant hour1 = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                    .minusSeconds(7200);
            Instant hour2 = hour1.plusSeconds(3600);

            ledger.post(posting(company, available, client, available, "USD", "100"));
            long cut1 = ledger.cutBalanceSnapshot(hour1);
            assertEquals(3, cut1); // company + client + other

            // Activity between the two cuts: bank funds client, client pays other.
            Transfer first = ledger.post(posting(company, available, client, available, "USD", "50"));
            Transfer second = ledger.post(posting(client, available, other, available, "USD", "30"));
            // Global ledger order: seq increases in commit order.
            assertTrue(second.seq() > first.seq());
            long cut2 = ledger.cutBalanceSnapshot(hour2);
            assertEquals(3, cut2);

            // Rows carry UTC calendar fields of their hour and prior closing balance.
            List<BalanceSnapshot> snap2 = ledger.snapshots(hour2);
            assertEquals(3, snap2.size());
            BalanceSnapshot clientRow = snapshotFor(snap2, client);
            assertEquals(hour2, clientRow.snapshotHour());
            assertEquals(hour2.atZone(java.time.ZoneOffset.UTC).getYear(), clientRow.year());
            assertEquals(hour2.atZone(java.time.ZoneOffset.UTC).getMonthValue(), clientRow.month());
            assertEquals(hour2.atZone(java.time.ZoneOffset.UTC).getDayOfMonth(), clientRow.day());
            assertEquals(hour2.atZone(java.time.ZoneOffset.UTC).getHour(), clientRow.hour());
            // 100 (hour1 close) + 50 - 30
            assertEquals(0, new BigDecimal("120").compareTo(clientRow.balance()));
            assertEquals(0, new BigDecimal("100").compareTo(clientRow.previousBalance()));

            // Movement 4pm-to-4pm style: net change between two snapshot hours.
            List<SnapshotMovement> moves = ledger.snapshotMovements(hour1, hour2);
            // client +20, other +30 -> CLIENT group nets +50; COMPANY (BANK) -50.
            SnapshotMovement row = movementFor(moves, available, "USD", "CLIENT");
            assertEquals(0, new BigDecimal("50").compareTo(row.movement()));
            assertEquals(0, new BigDecimal("100").compareTo(row.openingBalance()));
            assertEquals(0, new BigDecimal("150").compareTo(row.closingBalance()));

            // COMPANY (BANK) pool funded the client: -50 in the same span.
            SnapshotMovement bank = movementFor(moves, available, "USD", "BANK");
            assertEquals(0, new BigDecimal("-50").compareTo(bank.movement()));

            // Conservation: movements across all groups sum to zero.
            BigDecimal total = BigDecimal.ZERO;
            for (SnapshotMovement m : moves) {
                total = total.add(m.movement());
            }
            assertEquals(0, BigDecimal.ZERO.compareTo(total));

            // Re-cutting the same hour replaces rows, not duplicates.
            long recut = ledger.cutBalanceSnapshot(hour2);
            assertEquals(3, recut);
            assertEquals(3, ledger.snapshots(hour2).size());

            // Non-full-hour timestamps are rejected.
            assertThrows(LedgerViolation.class, () -> ledger.cutBalanceSnapshot(hour2.plusSeconds(90)));
        }
    }

    @Test
    @Timeout(60)
    void snapshotPartitionsCoverRollingTwelveMonthsAhead() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            // Migrate pre-created the current month plus 12 ahead (plus any data months).
            assertTrue(partitionCount() >= 13);

            // A far-future month has no partition yet; the ensure helper must
            // create exactly it, then be a no-op on re-run.
            Instant far = Instant.now().atZone(java.time.ZoneOffset.UTC).plusMonths(15).toInstant();
            assertEquals(1, ledger.ensureSnapshotPartitions(far, 0));
            assertEquals(0, ledger.ensureSnapshotPartitions(far, 0));

            java.time.ZonedDateTime farMonth = far.atZone(java.time.ZoneOffset.UTC)
                    .toLocalDate().withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC);
            String expected = String.format("pgledger_balance_snapshots_y%04dm%02d",
                    farMonth.getYear(), farMonth.getMonthValue());
            assertTrue(partitionExists(expected));

            // Belt-and-braces hourly ensure for the current month is a no-op.
            assertEquals(0, ledger.ensureSnapshotPartitions(
                    Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS), 0));
        }
    }

    @Test
    @Timeout(60)
    void legacySnapshotTableMigratesToPartitionsWithData() throws Exception {
        // Simulate a pre-partitioning volume: plain table holding rows from an
        // older month. Migrate must convert it, keep the rows, and cover the
        // data month plus the rolling 12 ahead.
        Instant dataHour = Instant.now().atZone(java.time.ZoneOffset.UTC).minusMonths(4)
                .toInstant().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        try (Connection conn = dataSource().getConnection();
             java.sql.Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS pgledger_balance_snapshots CASCADE");
            st.execute("""
                    CREATE TABLE pgledger_balance_snapshots (
                        snapshot_hour TIMESTAMPTZ NOT NULL,
                        account_pk TEXT NOT NULL,
                        account_id TEXT NOT NULL,
                        balance_type_id INT NOT NULL,
                        currency_id INT NOT NULL,
                        account_class_id INT NOT NULL,
                        year INT NOT NULL, month INT NOT NULL, day INT NOT NULL, hour INT NOT NULL,
                        balance NUMERIC NOT NULL, previous_balance NUMERIC NOT NULL,
                        version BIGINT NOT NULL, deleted BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL,
                        PRIMARY KEY (snapshot_hour, account_pk)
                    )""");
            st.execute("INSERT INTO pgledger_balance_snapshots VALUES ('" + dataHour
                    + "', 'PK1', 'ACC1', 1, 1, 1, 1, 1, 1, 0, 5, 0, 0, false, now())");
        }
        PostgresLedgerStore.migrate(dataSource());

        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM pg_partitioned_table WHERE partrelid = 'pgledger_balance_snapshots'::regclass");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertEquals(1, rs.getInt(1), "snapshot table must be partitioned after migrate");
        }
        assertEquals(1, snapshotRowCount());
        java.time.ZonedDateTime dataMonth = dataHour.atZone(java.time.ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1).atStartOfDay(java.time.ZoneOffset.UTC);
        assertTrue(partitionExists(String.format("pgledger_balance_snapshots_y%04dm%02d",
                dataMonth.getYear(), dataMonth.getMonthValue())));
        // Data month (4 back) through current + 12 ahead.
        assertTrue(partitionCount() >= 17);
    }

    private static int snapshotRowCount() throws SQLException {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM pgledger_balance_snapshots");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int partitionCount() throws SQLException {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM pg_inherits WHERE inhparent = 'pgledger_balance_snapshots'::regclass");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean partitionExists(String name) throws SQLException {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static BalanceSnapshot snapshotFor(List<BalanceSnapshot> rows, String accountId) {
        for (BalanceSnapshot row : rows) {
            if (accountId.equals(row.accountId())) {
                return row;
            }
        }
        return fail("snapshot row for " + accountId);
    }

    private static SnapshotMovement movementFor(List<SnapshotMovement> rows, String type, String currency, String klass) {
        for (SnapshotMovement row : rows) {
            if (type.equals(row.balanceType()) && currency.equals(row.currency()) && klass.equals(row.accountClass())) {
                return row;
            }
        }
        return fail("movement row for " + type + "/" + currency + "/" + klass);
    }

    @Test
    @Timeout(60)
    void oppositeTransfersLockInSortedIdOrder() throws Exception {
        try (Nodes nodes = new Nodes()) {
            PgLedger ledger = nodes.ledger;
            String prefix = id("OPP");
            String available = prefix + "A";
            String a = prefix + "A1";
            String b = prefix + "B1";
            String c = prefix + "C1";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            // Hub c funds both sides and may sit below zero: BANK class override.
            ledger.createAccount(new CreateAccount(c, available, "USD", c, null, "BANK"));
            ledger.createAccount(account(a, available, "USD"));
            ledger.createAccount(account(b, available, "USD"));
            ledger.post(posting(c, available, a, available, "USD", "1000"));
            ledger.post(posting(c, available, b, available, "USD", "1000"));
            nodes.awaitCatchUp();
            long journalsBefore = ledger.journals(0, 1).total();
        int rounds = 100;
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
                Future<Integer> left = pool.submit(() -> run(ledger, start, rounds, b, a, available));
                Future<Integer> right = pool.submit(() -> run(ledger, start, rounds, a, b, available));
            assertEquals(rounds, left.get());
            assertEquals(rounds, right.get());
        } finally {
            pool.shutdownNow();
        }
            nodes.awaitCatchUp();
            BigDecimal leftBalance = ledger.balance(a, available, "USD").balance();
            BigDecimal rightBalance = ledger.balance(b, available, "USD").balance();
            assertEquals(0, new BigDecimal("2000").compareTo(leftBalance.add(rightBalance)));
            assertEquals(journalsBefore + rounds * 2L, ledger.journals(0, 1).total());
        }
    }

    @Test
    void sqlScriptsKeepDollarQuoteBodies() {
        List<String> sample = SqlScripts.statements("SELECT 1; CREATE FUNCTION f() AS $$ SELECT ';' $$ LANGUAGE sql;");
        assertEquals(2, sample.size());
        assertTrue(sample.get(1).contains("SELECT ';'"));
    }

    @Test
    void sqlScriptsIgnoreSemicolonsInLineComments() {
        List<String> sample = SqlScripts.statements("-- note; keep\nSET timezone TO 'UTC';\nSELECT 1;");
        assertEquals(2, sample.size());
        assertTrue(sample.get(0).startsWith("-- note; keep"));
        assertTrue(sample.get(0).contains("SET timezone TO 'UTC'"));
        assertEquals("SELECT 1", sample.get(1));
    }

    private static int run(PgLedger ledger, CyclicBarrier start, int rounds, String from, String to, String type)
            throws Exception {
        start.await();
        for (int i = 0; i < rounds; i++) {
            ledger.post(posting(from, type, to, type, "USD", "1"));
        }
        return rounds;
    }

    private static void assertNewerFirst(Transfer previous, Transfer next) {
        int byTime = previous.createdAt().compareTo(next.createdAt());
        assertTrue(byTime > 0 || (byTime == 0 && previous.id().compareTo(next.id()) > 0),
                previous.id() + " before " + next.id());
    }

    private static int countCode(List<BalanceType> types, String code) {
        int n = 0;
        for (int i = 0; i < types.size(); i++) {
            if (code.equals(types.get(i).code())) {
                n++;
            }
        }
        return n;
    }

    private static CreateAccount account(String accountId, String balanceType, String currency) {
        return new CreateAccount(accountId, balanceType, currency, accountId, null, null);
    }

    private static CashMovement cash(String requestId, String accountId, String balanceType, BigDecimal amount) {
        return new CashMovement(requestId, accountId, balanceType, "USD", amount);
    }

    private static int bankShard(String requestId, int poolSize) throws SQLException {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT pgledger_bank_shard(?, ?)")) {
            ps.setString(1, requestId);
            ps.setInt(2, poolSize);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("shard");
                }
                return rs.getInt(1);
            }
        }
    }

    private static String[] requestPair(int poolSize, boolean sameShard) throws SQLException {
        String first = id("PAIR");
        int shard = bankShard(first, poolSize);
        for (int i = 0; i < 4096; i++) {
            String next = id("PAIR");
            int other = bankShard(next, poolSize);
            if (sameShard == (other == shard)) {
                return new String[] {first, next};
            }
        }
        fail("request ids");
        return new String[0];
    }

    private static String[] requestPairOnShard(int poolSize, int shard) throws SQLException {
        String[] ids = new String[2];
        int found = 0;
        for (int i = 0; i < 8192 && found < 2; i++) {
            String requestId = id("SAME");
            if (bankShard(requestId, poolSize) == shard) {
                ids[found++] = requestId;
            }
        }
        if (found < 2) {
            fail("request ids");
        }
        return ids;
    }

    private static String requestForShard(int poolSize, int shard) throws SQLException {
        for (int i = 0; i < 4096; i++) {
            String requestId = id("AIM");
            if (bankShard(requestId, poolSize) == shard) {
                return requestId;
            }
        }
        fail("request id");
        return "";
    }

    private static int shardOf(String accountId) {
        int dash = accountId.lastIndexOf('-');
        return Integer.parseInt(accountId.substring(dash + 1));
    }

    private static int nonzero(List<Account> shards) {
        int n = 0;
        for (int i = 0; i < shards.size(); i++) {
            if (shards.get(i).balance().signum() != 0) {
                n++;
            }
        }
        return n;
    }

    private static BigDecimal sum(List<Account> shards) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < shards.size(); i++) {
            total = total.add(shards.get(i).balance());
        }
        return total;
    }

    private static boolean journalHas(PgLedger ledger, String transferId) {
        for (int page = 0; page < 40; page++) {
            JournalPage journalPage = ledger.journals(page, 50);
            for (int i = 0; i < journalPage.transfers().size(); i++) {
                Transfer transfer = journalPage.transfers().get(i);
                if (transferId.equals(transfer.id())) {
                    return transfer.entries().size() == 2;
                }
            }
            if (!journalPage.hasNext()) {
                return false;
            }
        }
        return false;
    }

    private static boolean awaitLock(DataSource dataSource, String querySnippet) throws Exception {
        long start = System.nanoTime();
        while (System.nanoTime() - start < 15_000_000_000L) {
            if (waiting(dataSource, querySnippet)) {
                return true;
            }
            Thread.sleep(20L);
        }
        return false;
    }

    private static boolean waiting(DataSource dataSource, String querySnippet) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM pg_stat_activity
                         WHERE wait_event_type = 'Lock'
                           AND query LIKE ?
                           AND pid <> pg_backend_pid()
                     )
                     """)) {
            ps.setString(1, "%" + querySnippet + "%");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static Posting posting(String fromAccount, String fromType, String toAccount, String toType,
                                   String currency, String amount) {
        return new Posting(fromAccount, fromType, toAccount, toType, currency,
                new BigDecimal(amount), id("REQ"), null, null, false);
    }

    private static String id(String prefix) {
        return prefix + Long.toUnsignedString(IDS.incrementAndGet(), 36)
                + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private static final class Nodes implements AutoCloseable {
        private final PgLedger ledger;

        private Nodes() {
            DataSource dataSource = dataSource();
            ledger = PgLedger.postgres(dataSource, dataSource);
        }

        private void awaitCatchUp() {
            // Single primary: reader is the same DataSource, so writes are already visible.
        }

        @Override
        public void close() {
            ledger.close();
        }
    }

    private static DataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl("postgres", "postgres"));
        dataSource.setUser("postgres");
        dataSource.setPassword("postgres");
        dataSource.setConnectTimeout(5);
        return dataSource;
    }
}
