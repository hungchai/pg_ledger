package io.zodia.pgledger;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerViolation;
import io.zodia.pgledger.store.SqlScripts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgLedgerTest {
    @Test
    void createListsEveryBalanceTypeAndRejectsDuplicates() {
        PgLedger ledger = PgLedger.inMemory();
        seedTypes(ledger);
        BalanceType availableType = ledger.createBalanceType(new CreateBalanceType("TRADEAHEAD", "Trade ahead", null));
        assertEquals("TRADEAHEAD", availableType.code());
        LedgerViolation duplicateType = assertThrows(LedgerViolation.class,
                () -> ledger.createBalanceType(new CreateBalanceType("AVAILABLE", "dup", null)));
        assertEquals("balance type already exists", duplicateType.getMessage());
        LedgerViolation missingType = assertThrows(LedgerViolation.class,
                () -> ledger.createAccount(account("CLIENT_ACC_001", "MISSING", "USD", false, true)));
        assertTrue(missingType.getMessage().contains("balance type not found"));

        Account available = ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "USD", false, true));
        Account locked = ledger.createAccount(account("CLIENT_ACC_001", "LOCKED", "USD", false, true));
        ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "EUR", false, true));
        assertEquals("CLIENT_ACC_001", available.accountId());
        assertEquals("AVAILABLE", available.balanceType());
        assertEquals(0, BigDecimal.ZERO.compareTo(available.balance()));
        assertEquals(0L, available.version());
        assertFalse(available.id().equals(locked.id()));

        List<Account> rows = ledger.balances("CLIENT_ACC_001");
        assertEquals(3, rows.size());
        assertEquals("AVAILABLE", rows.get(0).balanceType());
        assertEquals("EUR", rows.get(0).currency());
        assertEquals("AVAILABLE", rows.get(1).balanceType());
        assertEquals("USD", rows.get(1).currency());
        assertEquals("LOCKED", rows.get(2).balanceType());

        LedgerViolation duplicate = assertThrows(LedgerViolation.class,
                () -> ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "USD", false, true)));
        assertEquals("account already exists", duplicate.getMessage());
        assertNull(ledger.balance("missing", "AVAILABLE", "USD"));
        assertEquals(3, ledger.balanceTypes().size());
        ledger.close();
    }

    @Test
    void transferMovesOneBalanceRowAndRecordsEntries() {
        PgLedger ledger = PgLedger.inMemory();
        seedTypes(ledger);
        ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "USD", false, true));
        ledger.createAccount(account("CLIENT_ACC_001", "LOCKED", "USD", false, true));
        ledger.createAccount(account("COMPANY", "AVAILABLE", "USD", true, true));

        Transfer funded = ledger.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "100"));
        assertEquals("COMPANY", funded.fromAccountId());
        assertEquals("CLIENT_ACC_001", funded.toAccountId());
        assertEquals(2, funded.entries().size());
        assertEquals(0, new BigDecimal("-100").compareTo(funded.entries().get(0).amount()));
        assertEquals(0, new BigDecimal("100").compareTo(funded.entries().get(1).amount()));
        assertEquals(0, new BigDecimal("100").compareTo(funded.entries().get(1).currentBalance()));
        assertEquals(1L, funded.entries().get(1).version());

        Transfer held = ledger.post(posting("CLIENT_ACC_001", "AVAILABLE", "CLIENT_ACC_001", "LOCKED", "USD", "40"));
        assertEquals("AVAILABLE", held.fromBalanceType());
        assertEquals("LOCKED", held.toBalanceType());
        assertEquals(0, new BigDecimal("60").compareTo(ledger.balance("CLIENT_ACC_001", "AVAILABLE", "USD").balance()));
        assertEquals(0, new BigDecimal("40").compareTo(ledger.balance("CLIENT_ACC_001", "LOCKED", "USD").balance()));
        assertEquals(2L, ledger.balance("CLIENT_ACC_001", "AVAILABLE", "USD").version());
        ledger.close();
    }

    @Test
    void rejectsInsufficientBalanceCurrencyMismatchAndSameRow() {
        PgLedger ledger = PgLedger.inMemory();
        seedTypes(ledger);
        ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "USD", false, true));
        ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "EUR", true, true));
        ledger.createAccount(account("SINK", "AVAILABLE", "USD", true, false));
        ledger.createAccount(account("COMPANY", "AVAILABLE", "USD", true, true));
        ledger.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "10"));

        LedgerViolation poor = assertThrows(LedgerViolation.class,
                () -> ledger.post(posting("CLIENT_ACC_001", "AVAILABLE", "COMPANY", "AVAILABLE", "USD", "20")));
        assertTrue(poor.getMessage().contains("does not allow negative balance"));
        assertEquals(0, new BigDecimal("10").compareTo(ledger.balance("CLIENT_ACC_001", "AVAILABLE", "USD").balance()));
        assertEquals(1, ledger.journals(0, 50).total());

        LedgerViolation currency = assertThrows(LedgerViolation.class,
                () -> ledger.post(new Posting(
                        "CLIENT_ACC_001", "AVAILABLE", "SINK", "AVAILABLE", "EUR", BigDecimal.ONE, null)));
        assertTrue(currency.getMessage().contains("Cannot transfer between different currencies"));

        LedgerViolation missing = assertThrows(LedgerViolation.class,
                () -> ledger.post(posting("NOPE", "AVAILABLE", "COMPANY", "AVAILABLE", "USD", "1")));
        assertTrue(missing.getMessage().contains("Account not found"));

        LedgerViolation same = assertThrows(LedgerViolation.class,
                () -> ledger.post(posting("CLIENT_ACC_001", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "1")));
        assertTrue(same.getMessage().contains("Cannot transfer to the same account"));

        LedgerViolation positive = assertThrows(LedgerViolation.class,
                () -> ledger.post(posting("COMPANY", "AVAILABLE", "SINK", "AVAILABLE", "USD", "1")));
        assertTrue(positive.getMessage().contains("does not allow positive balance"));
        assertEquals(0, BigDecimal.ZERO.compareTo(ledger.balance("SINK", "AVAILABLE", "USD").balance()));

        LedgerViolation amount = assertThrows(LedgerViolation.class,
                () -> ledger.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "0")));
        assertTrue(amount.getMessage().contains("must be positive"));
        LedgerViolation blank = assertThrows(LedgerViolation.class,
                () -> ledger.createAccount(new CreateAccount("  ", "AVAILABLE", "USD", null, false, true, null)));
        assertEquals("account_id, balance_type, and currency are required", blank.getMessage());
        ledger.close();
    }

    @Test
    void journalsAreNewestFirst() {
        PgLedger ledger = PgLedger.inMemory();
        seedTypes(ledger);
        ledger.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "USD", false, true));
        ledger.createAccount(account("COMPANY", "AVAILABLE", "USD", true, true));
        ledger.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "10"));
        ledger.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "20"));
        ledger.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "30"));

        JournalPage first = ledger.journals(0, 2);
        assertEquals(3L, first.total());
        assertTrue(first.hasNext());
        assertEquals(0, new BigDecimal("30").compareTo(first.transfers().get(0).amount()));
        assertEquals(0, new BigDecimal("20").compareTo(first.transfers().get(1).amount()));
        assertEquals(2, first.transfers().get(0).entries().size());

        JournalPage second = ledger.journals(1, 2);
        assertFalse(second.hasNext());
        assertEquals(1, second.transfers().size());
        assertEquals(0, new BigDecimal("10").compareTo(second.transfers().get(0).amount()));

        JournalPage empty = ledger.journals(3, 2);
        assertEquals(0, empty.transfers().size());
        assertEquals(3L, empty.total());
        assertThrows(IllegalArgumentException.class, () -> ledger.journals(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> ledger.journals(0, 201));
        ledger.close();
    }

    @Test
    void omittedNameDefaultsAndMetadataRoundTrips() {
        PgLedger ledger = PgLedger.inMemory();
        seedTypes(ledger);
        Account created = ledger.createAccount(new CreateAccount(
                "CLIENT_ACC_001", "AVAILABLE", "USD", "  ", null, null, Map.of("desk", "fx")));
        assertEquals("CLIENT_ACC_001", created.name());
        assertTrue(created.allowNegativeBalance());
        assertTrue(created.allowPositiveBalance());
        assertEquals("fx", created.metadata().get("desk"));
        ledger.close();
    }

    @Test
    @Timeout(10)
    void oppositeTransfersLockInSortedIdOrder() throws Exception {
        PgLedger ledger = PgLedger.inMemory();
        seedTypes(ledger);
        ledger.createAccount(account("A", "AVAILABLE", "USD", true, true));
        ledger.createAccount(account("B", "AVAILABLE", "USD", true, true));
        ledger.createAccount(account("C", "AVAILABLE", "USD", true, true));
        ledger.post(posting("C", "AVAILABLE", "A", "AVAILABLE", "USD", "1000"));
        ledger.post(posting("C", "AVAILABLE", "B", "AVAILABLE", "USD", "1000"));
        int rounds = 100;
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> left = pool.submit(() -> run(ledger, start, rounds, "B", "A"));
            Future<Integer> right = pool.submit(() -> run(ledger, start, rounds, "A", "B"));
            assertEquals(rounds, left.get());
            assertEquals(rounds, right.get());
        } finally {
            pool.shutdownNow();
        }
        BigDecimal a = ledger.balance("A", "AVAILABLE", "USD").balance();
        BigDecimal b = ledger.balance("B", "AVAILABLE", "USD").balance();
        assertEquals(0, new BigDecimal("2000").compareTo(a.add(b)));
        assertEquals(2L + rounds * 2L, ledger.journals(0, 1).total());
        ledger.close();
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

    private static void seedTypes(PgLedger ledger) {
        ledger.createBalanceType(new CreateBalanceType("AVAILABLE", "Available", null));
        ledger.createBalanceType(new CreateBalanceType("LOCKED", "Locked", null));
    }

    private static int run(PgLedger ledger, CyclicBarrier start, int rounds, String from, String to) throws Exception {
        start.await();
        for (int i = 0; i < rounds; i++) {
            ledger.post(posting(from, "AVAILABLE", to, "AVAILABLE", "USD", "1"));
        }
        return rounds;
    }

    private static CreateAccount account(String accountId, String balanceType, String currency,
                                         boolean allowNegative, boolean allowPositive) {
        return new CreateAccount(accountId, balanceType, currency, accountId, allowNegative, allowPositive, null);
    }

    private static Posting posting(String fromAccount, String fromType, String toAccount, String toType,
                                   String currency, String amount) {
        return new Posting(fromAccount, fromType, toAccount, toType, currency, new BigDecimal(amount), null);
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
}
