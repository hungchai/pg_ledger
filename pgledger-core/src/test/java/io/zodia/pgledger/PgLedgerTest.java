package io.zodia.pgledger;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerViolation;
import io.zodia.pgledger.store.PostgresLedgerStore;
import io.zodia.pgledger.store.SqlScripts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PgLedgerTest {
    private static final String WRITER_URL = "jdbc:postgresql://localhost:5432/pgledger";
    private static final String READER_URL = "jdbc:postgresql://localhost:5433/pgledger";
    private static final String DB_USER = "pgledger";
    private static final String DB_PASSWORD = "pgledger";
    private static final long CATCH_UP_NS = 15_000_000_000L;
    private static final AtomicLong IDS = new AtomicLong();

    @Test
    void schemaMigrationIsSafeWhenInstancesStartTogether() throws Exception {
        DataSource writer = dataSource(WRITER_URL);
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
            LedgerViolation duplicateType = assertThrows(LedgerViolation.class,
                    () -> ledger.createBalanceType(new CreateBalanceType(available, "dup", null)));
            assertEquals("balance type already exists", duplicateType.getMessage());
            LedgerViolation missingType = assertThrows(LedgerViolation.class,
                    () -> ledger.createAccount(account(accountId, prefix + "M", "USD", false, true)));
            assertTrue(missingType.getMessage().contains("balance type not found"));

            Account availableRow = ledger.createAccount(account(accountId, available, "USD", false, true));
            Account lockedRow = ledger.createAccount(account(accountId, locked, "USD", false, true));
            ledger.createAccount(account(accountId, available, "EUR", false, true));
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
                    () -> ledger.createAccount(account(accountId, available, "USD", false, true)));
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
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            ledger.createBalanceType(new CreateBalanceType(locked, "Locked", null));
            ledger.createAccount(account(client, available, "USD", false, true));
            ledger.createAccount(account(client, locked, "USD", false, true));
            ledger.createAccount(account(company, available, "USD", true, true));

            Transfer funded = ledger.post(posting(company, available, client, available, "USD", "100"));
            assertEquals(company, funded.fromAccountId());
            assertEquals(client, funded.toAccountId());
            assertEquals(2, funded.entries().size());
            assertEquals(0, new BigDecimal("-100").compareTo(funded.entries().get(0).amount()));
            assertEquals(0, new BigDecimal("100").compareTo(funded.entries().get(1).amount()));
            assertEquals(0, new BigDecimal("100").compareTo(funded.entries().get(1).currentBalance()));
            assertEquals(1L, funded.entries().get(1).version());

            Transfer held = ledger.post(posting(client, available, client, locked, "USD", "40"));
            assertEquals(available, held.fromBalanceType());
            assertEquals(locked, held.toBalanceType());
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
            String client = prefix + "C";
            String sink = prefix + "S";
            String company = prefix + "CO";
            ledger.createBalanceType(new CreateBalanceType(available, "Available", null));
            ledger.createAccount(account(client, available, "USD", false, true));
            ledger.createAccount(account(client, available, "EUR", true, true));
            ledger.createAccount(account(sink, available, "USD", true, false));
            ledger.createAccount(account(company, available, "USD", true, true));
            nodes.awaitCatchUp();
            long journalsBefore = ledger.journals(0, 1).total();
            ledger.post(posting(company, available, client, available, "USD", "10"));

            LedgerViolation poor = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(client, available, company, available, "USD", "20")));
            assertTrue(poor.getMessage().contains("does not allow negative balance"));
            nodes.awaitCatchUp();
            assertEquals(0, new BigDecimal("10").compareTo(ledger.balance(client, available, "USD").balance()));
            assertEquals(journalsBefore + 1L, ledger.journals(0, 1).total());

            LedgerViolation currency = assertThrows(LedgerViolation.class,
                    () -> ledger.post(new Posting(
                            client, available, sink, available, "EUR", BigDecimal.ONE, null)));
            assertTrue(currency.getMessage().contains("Cannot transfer between different currencies"));

            LedgerViolation missing = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(prefix + "NOPE", available, company, available, "USD", "1")));
            assertTrue(missing.getMessage().contains("Account not found"));

            LedgerViolation same = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(client, available, client, available, "USD", "1")));
            assertTrue(same.getMessage().contains("Cannot transfer to the same account"));

            LedgerViolation positive = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(company, available, sink, available, "USD", "1")));
            assertTrue(positive.getMessage().contains("does not allow positive balance"));
            assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balance(sink, available, "USD").balance()));

            LedgerViolation amount = assertThrows(LedgerViolation.class,
                    () -> ledger.post(posting(company, available, client, available, "USD", "0")));
            assertTrue(amount.getMessage().contains("must be positive"));
            LedgerViolation blank = assertThrows(LedgerViolation.class,
                    () -> ledger.createAccount(new CreateAccount("  ", available, "USD", null, false, true, null)));
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
            ledger.createAccount(account(client, available, "USD", false, true));
            ledger.createAccount(account(company, available, "USD", true, true));
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
                    accountId, available, "USD", "  ", null, null, Map.of("desk", "fx")));
            assertEquals(accountId, created.name());
            assertTrue(created.allowNegativeBalance());
            assertTrue(created.allowPositiveBalance());
            assertEquals("fx", created.metadata().get("desk"));
        }
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
            ledger.createAccount(account(a, available, "USD", true, true));
            ledger.createAccount(account(b, available, "USD", true, true));
            ledger.createAccount(account(c, available, "USD", true, true));
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
    void schemaScriptsKeepFunctionBodiesIntact() throws Exception {
        String tables = Files.readString(Path.of("db/V001__ledger.sql"));
        String functions = Files.readString(Path.of("db/V002__functions.sql"));
        List<String> tableStatements = SqlScripts.statements(tables);
        List<String> functionStatements = SqlScripts.statements(functions);
        assertTrue(tableStatements.stream().anyMatch(sql -> sql.contains("CREATE TABLE") && sql.contains("pgledger_accounts")));
        assertTrue(tableStatements.stream().anyMatch(sql -> sql.contains("CREATE TABLE") && sql.contains("pgledger_balance_types")));
        assertTrue(tableStatements.stream().anyMatch(sql -> sql.contains("balance_type") && sql.contains("account_id")));
        assertTrue(functionStatements.stream().anyMatch(sql -> sql.contains("pgledger_create_balance_type")));
        assertTrue(functionStatements.stream().anyMatch(sql -> sql.contains("pgledger_create_account")));
        assertTrue(functionStatements.stream().anyMatch(sql -> sql.contains("pgledger_create_transfer")));
        assertTrue(functionStatements.stream().noneMatch(sql -> sql.contains("pgledger_post(")));
        for (int i = 0; i < functionStatements.size(); i++) {
            String sql = functionStatements.get(i);
            assertEquals(0, count(sql, "$$") % 2, sql);
        }
        List<String> sample = SqlScripts.statements("SELECT 1; CREATE FUNCTION f() AS $$ SELECT ';' $$ LANGUAGE sql;");
        assertEquals(2, sample.size());
        assertTrue(sample.get(1).contains("SELECT ';'"));
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

    private static CreateAccount account(String accountId, String balanceType, String currency,
                                         boolean allowNegative, boolean allowPositive) {
        return new CreateAccount(accountId, balanceType, currency, accountId, allowNegative, allowPositive, null);
    }

    private static Posting posting(String fromAccount, String fromType, String toAccount, String toType,
                                   String currency, String amount) {
        return new Posting(fromAccount, fromType, toAccount, toType, currency, new BigDecimal(amount), null);
    }

    private static String id(String prefix) {
        return prefix + Long.toUnsignedString(IDS.incrementAndGet(), 36)
                + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private static int count(String text, String token) {
        int n = 0;
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + token.length();
        }
        return n;
    }

    private static final class Nodes implements AutoCloseable {
        private final DataSource writer;
        private final DataSource reader;
        private final PgLedger ledger;

        private Nodes() throws SQLException {
            writer = dataSource(WRITER_URL);
            reader = dataSource(READER_URL);
            if (queryBoolean(writer, "SELECT pg_is_in_recovery()")) {
                throw new IllegalStateException("writer url is a replica");
            }
            if (!queryBoolean(reader, "SELECT pg_is_in_recovery()")) {
                throw new IllegalStateException("reader url is not a replica");
            }
            ledger = PgLedger.postgres(writer, reader);
        }

        private void awaitCatchUp() throws Exception {
            long start = System.nanoTime();
            String lsn = queryText(writer, "SELECT pg_current_wal_lsn()::text");
            while (System.nanoTime() - start < CATCH_UP_NS) {
                if (replayed(reader, lsn)) {
                    return;
                }
                Thread.sleep(20L);
            }
            fail("reader did not replay " + lsn);
        }

        @Override
        public void close() {
            ledger.close();
        }
    }

    private static boolean replayed(DataSource dataSource, String lsn) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT pg_is_in_recovery()
                        AND coalesce(pg_last_wal_replay_lsn() >= ?::pg_lsn, false)
                     """)) {
            ps.setString(1, lsn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static String queryText(DataSource dataSource, String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException(sql);
            }
            return rs.getString(1);
        }
    }

    private static boolean queryBoolean(DataSource dataSource, String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException(sql);
            }
            return rs.getBoolean(1);
        }
    }

    private static PGSimpleDataSource dataSource(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        dataSource.setConnectTimeout(5);
        return dataSource;
    }
}
