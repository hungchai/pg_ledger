package io.zodia.pgledger.rest;

import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.Entry;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.client.PgLedgerClient;
import io.zodia.pgledger.client.PgLedgerClientConfig;
import io.zodia.pgledger.client.PgLedgerClientException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Stress against docker writer :5432 and streaming reader :5433.
 * Starts its own HTTP server. Truncates ledger tables in {@code @BeforeAll}.
 *
 * <p>docker compose up -d &amp;&amp; gradle :pgledger-restful:stressTest
 */
@Tag("stress")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 25, unit = TimeUnit.MINUTES)
class PgLedgerStressTest {
    private static final String AVAILABLE = "AVAILABLE";
    private static final String LOCKED = "LOCKED";
    private static final String USD = "USD";
    private static final String COMPANY = "COMPANY";
    private static final long MAX_LAG_MS = Long.getLong("pgledger.stress.maxLagMs", 15_000L);
    private static final int POSTS = Integer.getInteger("pgledger.stress.posts", 20);
    private static final String SUM_BROKEN = """
            SELECT currency, sum(balance) AS balance
            FROM pgledger_accounts
            GROUP BY currency
            HAVING sum(balance) <> 0
            """;
    private static final String ROW_BROKEN = """
            SELECT a.account_id, a.balance_type, a.currency, a.balance, a.version,
                   count(e.id) AS entries,
                   coalesce(max(e.account_version), 0) AS max_version,
                   coalesce(sum(e.amount), 0) AS moved
            FROM pgledger_accounts a
            LEFT JOIN pgledger_entries e ON e.account_id = a.id
            GROUP BY a.id
            HAVING a.version <> count(e.id)
                OR a.version <> coalesce(max(e.account_version), 0)
                OR a.balance <> coalesce(sum(e.amount), 0)
            """;
    private static final String ORPHAN_TRANSFERS = """
            SELECT t.id, count(e.id) AS entries, coalesce(sum(e.amount), 0) AS net
            FROM pgledger_transfers t
            LEFT JOIN pgledger_entries e ON e.transfer_id = t.id
            GROUP BY t.id
            HAVING count(e.id) <> 2 OR coalesce(sum(e.amount), 0) <> 0
            """;
    private static final String NEGATIVE = """
            SELECT account_id, balance_type, currency, balance
            FROM pgledger_accounts
            WHERE NOT allow_negative_balance AND balance < 0
            """;
    private static final String ENTRY_CHAIN = """
            SELECT id, account_previous_balance, amount, account_current_balance, account_version
            FROM pgledger_entries
            WHERE account_current_balance <> account_previous_balance + amount
               OR account_version < 1
            """;

    private static DataSource writer;
    private static DataSource reader;
    private static PgLedger ledger;
    private static PgLedgerServer server;
    private static PgLedgerClient client;
    private static long deadlockBaseline;

    @BeforeAll
    static void up() throws Exception {
        String user = config("pgledger.user", "PGLEDGER_JDBC_USER", "pgledger");
        String password = config("pgledger.password", "PGLEDGER_JDBC_PASSWORD", "pgledger");
        writer = dataSource(config("pgledger.writer.url", "PGLEDGER_WRITER_JDBC_URL",
                "jdbc:postgresql://localhost:5432/pgledger"), user, password);
        reader = dataSource(config("pgledger.reader.url", "PGLEDGER_READER_JDBC_URL",
                "jdbc:postgresql://localhost:5433/pgledger"), user, password);
        try {
            assertFalse(queryBoolean(writer, "SELECT pg_is_in_recovery()"), "writer url is a replica");
            assertTrue(queryBoolean(reader, "SELECT pg_is_in_recovery()"), "reader url is not a replica");
        } catch (SQLException e) {
            throw new IllegalStateException("writer/reader not reachable; docker compose up -d", e);
        }
        ledger = PgLedger.postgres(writer, reader);
        server = PgLedgerServer.start(ledger, 0);
        client = new PgLedgerClient(new PgLedgerClientConfig(
                URI.create("http://127.0.0.1:" + server.port()),
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                0));
        execute(writer, """
                TRUNCATE TABLE pgledger_entries, pgledger_transfers, pgledger_accounts, pgledger_balance_types CASCADE
                """);
        deadlockBaseline = queryLong(writer,
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()");
        client.createBalanceType(new CreateBalanceType(AVAILABLE, "Available", null));
        client.createBalanceType(new CreateBalanceType(LOCKED, "Locked", null));
        client.createAccount(account(COMPANY, AVAILABLE, true));
        awaitCatchUp();
        assertNoDeadlocks();
    }

    @AfterAll
    static void down() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        if (ledger != null) {
            ledger.close();
        }
    }

    @Test
    @Order(1)
    void setupRace() throws Exception {
        String code = "RACE_" + suffix();
        int threads = 32;
        int[] status = new int[threads];
        fill(threads, 1, (thread, seq) -> {
            client.createBalanceType(new CreateBalanceType(code, code, null));
            return client.status();
        }, status, new long[threads]);
        assertEquals(1, count(status, 200), "balance type winners");
        assertEquals(threads - 1, count(status, 422), "balance type rejects");
        assertOnly(status, 200, 422);

        String accountId = "RACE_ACCT_" + suffix();
        fill(threads, 1, (thread, seq) -> {
            client.createAccount(account(accountId, AVAILABLE, false));
            return client.status();
        }, status, new long[threads]);
        assertEquals(1, count(status, 200), "account winners");
        assertEquals(threads - 1, count(status, 422), "account rejects");
        assertOnly(status, 200, 422);
        Account created = readBalance(accountId, AVAILABLE);
        assertEquals(0, BigDecimal.ZERO.compareTo(created.balance()));
        assertEquals(0L, created.version());
        assertInvariants();
    }

    @Test
    @Order(2)
    void hotAccountContention() throws Exception {
        String hot = "HOT_" + suffix();
        client.createAccount(account(hot, AVAILABLE, true));
        int threads = 32;
        int posts = 20;
        int[] status = new int[threads * posts];
        fill(threads, posts, (thread, seq) -> {
            if ((thread & 1) == 0) {
                client.post(posting(COMPANY, AVAILABLE, hot, AVAILABLE, BigDecimal.ONE));
            } else {
                client.post(posting(hot, AVAILABLE, COMPANY, AVAILABLE, BigDecimal.ONE));
            }
            return client.status();
        }, status, new long[threads * posts]);
        assertOnly(status, 200);
        int credits = (threads / 2) * posts;
        int debits = credits;
        Account account = readBalance(hot, AVAILABLE);
        assertEquals(0, BigDecimal.valueOf((long) credits - debits).compareTo(account.balance()));
        assertEquals((long) threads * posts, account.version());
        assertInvariants();
    }

    @Test
    @Order(3)
    void oppositeTransfersAvoidDeadlock() throws Exception {
        String left = "OPP_A_" + suffix();
        String right = "OPP_B_" + suffix();
        client.createAccount(account(left, AVAILABLE, true));
        client.createAccount(account(right, AVAILABLE, true));
        int wings = 16;
        int posts = 30;
        int threads = wings * 2;
        int[] status = new int[threads * posts];
        fill(threads, posts, (thread, seq) -> {
            if (thread < wings) {
                client.post(posting(left, AVAILABLE, right, AVAILABLE, BigDecimal.ONE));
            } else {
                client.post(posting(right, AVAILABLE, left, AVAILABLE, BigDecimal.ONE));
            }
            return client.status();
        }, status, new long[threads * posts]);
        assertOnly(status, 200);
        Account a = readBalance(left, AVAILABLE);
        Account b = readBalance(right, AVAILABLE);
        assertEquals(0, a.balance().add(b.balance()).compareTo(BigDecimal.ZERO));
        long touches = (long) threads * posts;
        assertEquals(touches, a.version());
        assertEquals(touches, b.version());
        assertInvariants();
    }

    @Test
    @Order(4)
    void allowNegativeRace() throws Exception {
        int funded = 80;
        String id = "NONNEG_" + suffix();
        String sink = "SINK_" + suffix();
        client.createAccount(account(id, AVAILABLE, false));
        client.createAccount(account(sink, AVAILABLE, true));
        client.post(posting(COMPANY, AVAILABLE, id, AVAILABLE, BigDecimal.valueOf(funded)));
        int threads = funded + 1;
        int[] status = new int[threads];
        fill(threads, 1, (thread, seq) -> {
            client.post(posting(id, AVAILABLE, sink, AVAILABLE, BigDecimal.ONE));
            return client.status();
        }, status, new long[threads]);
        assertEquals(funded, count(status, 200));
        assertEquals(1, count(status, 422));
        assertOnly(status, 200, 422);
        Account account = readBalance(id, AVAILABLE);
        assertEquals(0, BigDecimal.ZERO.compareTo(account.balance()));
        assertEquals(1L + funded, account.version());
        Account sinkAccount = readBalance(sink, AVAILABLE);
        assertEquals(0, BigDecimal.valueOf(funded).compareTo(sinkAccount.balance()));
        assertInvariants();
    }

    @Test
    @Order(5)
    void crossBalanceTypeConserves() throws Exception {
        int funded = 40;
        int attempts = 70;
        String id = "CROSS_" + suffix();
        client.createAccount(account(id, AVAILABLE, false));
        client.createAccount(account(id, LOCKED, false));
        client.post(posting(COMPANY, AVAILABLE, id, AVAILABLE, BigDecimal.valueOf(funded)));
        int[] status = new int[attempts];
        fill(attempts, 1, (thread, seq) -> {
            client.post(posting(id, AVAILABLE, id, LOCKED, BigDecimal.ONE));
            return client.status();
        }, status, new long[attempts]);
        assertEquals(funded, count(status, 200));
        assertEquals(attempts - funded, count(status, 422));
        assertOnly(status, 200, 422);
        Account available = readBalance(id, AVAILABLE);
        Account locked = readBalance(id, LOCKED);
        assertEquals(0, BigDecimal.ZERO.compareTo(available.balance()));
        assertEquals(0, BigDecimal.valueOf(funded).compareTo(locked.balance()));
        assertEquals(0, available.balance().add(locked.balance()).compareTo(BigDecimal.valueOf(funded)));
        assertInvariants();
    }

    @Test
    @Order(6)
    void readerRejectsWritesAndCatchesUp() throws Exception {
        assertReadOnlyReplica();
        String id = "LAG_" + suffix();
        client.createAccount(account(id, AVAILABLE, true));
        client.post(posting(COMPANY, AVAILABLE, id, AVAILABLE, BigDecimal.ONE));
        awaitCatchUp();

        int writers = 24;
        int posts = 15;
        int readers = 16;
        AtomicBoolean run = new AtomicBoolean(true);
        AtomicInteger readCount = new AtomicInteger();
        AtomicInteger read5xx = new AtomicInteger();
        AtomicInteger readOther = new AtomicInteger();
        int[] status = new int[writers * posts];
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] background = new Future<?>[readers];
            for (int i = 0; i < readers; i++) {
                background[i] = pool.submit(() -> {
                    while (run.get()) {
                        readCount.incrementAndGet();
                        probeRead(id, read5xx, readOther);
                    }
                    return null;
                });
            }
            fill(writers, posts, (thread, seq) -> {
                client.post(posting(COMPANY, AVAILABLE, id, AVAILABLE, BigDecimal.ONE));
                if (!PgLedgerServer.WRITER.equals(client.role())) {
                    return -2;
                }
                return client.status();
            }, status, new long[writers * posts]);
            run.set(false);
            for (int i = 0; i < background.length; i++) {
                background[i].get(1, TimeUnit.MINUTES);
            }
        }
        assertOnly(status, 200);
        assertEquals(0, read5xx.get(), "reader 5xx during writes");
        assertEquals(0, readOther.get(), "reader status/role during writes");
        assertTrue(readCount.get() > 0, "no reads during writes");

        BigDecimal writerBalance = writerBalance(id, AVAILABLE);
        long writerTransfers = queryLong(writer, "SELECT count(*) FROM pgledger_transfers");
        long lagStart = System.nanoTime();
        long deadline = lagStart + MAX_LAG_MS * 1_000_000L;
        boolean matched = false;
        while (System.nanoTime() < deadline) {
            Account seen = client.balance(id, AVAILABLE, USD);
            if (client.status() >= 500) {
                fail("reader balance 5xx during catch-up");
            }
            JournalPage page = client.journals(0, 1);
            if (seen != null
                    && seen.balance().compareTo(writerBalance) == 0
                    && page.total() == writerTransfers
                    && PgLedgerServer.READER.equals(client.role())) {
                matched = true;
                break;
            }
            Thread.sleep(10L);
        }
        long lagMs = (System.nanoTime() - lagStart) / 1_000_000L;
        System.out.println("reader lag ms=" + lagMs
                + " bound=" + MAX_LAG_MS
                + " readsDuringWrite=" + readCount.get()
                + " writerBalance=" + writerBalance.toPlainString()
                + " transfers=" + writerTransfers);
        assertTrue(matched, "reader lag " + lagMs + " ms exceeded " + MAX_LAG_MS);
        assertInvariants();
    }

    @Test
    @Order(7)
    void throughputAndLatency() throws Exception {
        int[] levels = levels();
        for (int i = 0; i < levels.length; i++) {
            runLevel(levels[i], POSTS);
        }
        assertInvariants();
    }

    @Test
    @Order(8)
    void journalPaginationUnderGrowth() throws Exception {
        String src = "JRN_SRC_" + suffix();
        String dst = "JRN_DST_" + suffix();
        client.createAccount(account(src, AVAILABLE, true));
        client.createAccount(account(dst, AVAILABLE, true));
        int threads = 10;
        int posts = 25;
        int[] status = new int[threads * posts];
        fill(threads, posts, (thread, seq) -> {
            client.post(posting(src, AVAILABLE, dst, AVAILABLE, BigDecimal.ONE));
            return client.status();
        }, status, new long[threads * posts]);
        assertOnly(status, 200);

        PgLedgerClientException badSize = assertThrows(PgLedgerClientException.class, () -> client.journals(0, 201));
        assertEquals(400, badSize.status());

        awaitCatchUp();
        long total = queryLong(writer, "SELECT count(*) FROM pgledger_transfers");
        assertTrue(total >= (long) threads * posts);
        Transfer previous = null;
        long seen = 0L;
        int page = 0;
        while (true) {
            JournalPage journalPage = client.journals(page, 200);
            assertEquals(PgLedgerServer.READER, client.role());
            assertEquals(total, journalPage.total());
            assertTrue(journalPage.transfers().size() <= 200);
            for (int i = 0; i < journalPage.transfers().size(); i++) {
                Transfer transfer = journalPage.transfers().get(i);
                if (previous != null) {
                    assertTrue(newerFirst(previous, transfer), previous.id() + " before " + transfer.id());
                }
                assertEquals(2, transfer.entries().size(), transfer.id());
                BigDecimal net = BigDecimal.ZERO;
                for (int e = 0; e < transfer.entries().size(); e++) {
                    Entry entry = transfer.entries().get(e);
                    net = net.add(entry.amount());
                }
                assertEquals(0, net.compareTo(BigDecimal.ZERO), transfer.id());
                previous = transfer;
                seen++;
            }
            if (!journalPage.hasNext()) {
                break;
            }
            assertEquals(200, journalPage.transfers().size());
            page++;
            assertTrue(page < 100_000, "journal page walk did not end");
        }
        assertEquals(total, seen);
        JournalPage tail = client.journals(page + 1, 50);
        assertEquals(total, tail.total());
        assertEquals(0, tail.transfers().size());
        assertFalse(tail.hasNext());
        assertEquals(PgLedgerServer.READER, client.role());
        assertInvariants();
    }

    private static void runLevel(int clients, int posts) throws Exception {
        String prefix = "TP" + clients + "_";
        int[] created = new int[clients * 2];
        fill(clients, 2, (thread, seq) -> {
            String id = prefix + (seq == 0 ? "S" : "D") + thread;
            client.createAccount(account(id, AVAILABLE, true));
            return client.status();
        }, created, new long[clients * 2]);
        assertOnly(created, 200);

        int[] funded = new int[clients];
        fill(clients, 1, (thread, seq) -> {
            client.post(posting(COMPANY, AVAILABLE, prefix + "S" + thread, AVAILABLE, BigDecimal.valueOf(posts)));
            return client.status();
        }, funded, new long[clients]);
        assertOnly(funded, 200);
        awaitCatchUp();

        int[] postStatus = new int[clients * posts];
        long[] postNanos = new long[clients * posts];
        long postWall = fill(clients, posts, (thread, seq) -> {
            client.post(posting(prefix + "S" + thread, AVAILABLE, prefix + "D" + thread, AVAILABLE, BigDecimal.ONE));
            if (!PgLedgerServer.WRITER.equals(client.role())) {
                return -2;
            }
            return client.status();
        }, postStatus, postNanos);
        report("POST /postings", clients, postStatus, postNanos, postWall);
        assertLoad(postStatus, "POST clients=" + clients);

        int[] getStatus = new int[clients * posts];
        long[] getNanos = new long[clients * posts];
        long getWall = fill(clients, posts, (thread, seq) -> {
            Account account = client.balance(prefix + "D" + thread, AVAILABLE, USD);
            if (account == null || !PgLedgerServer.READER.equals(client.role())) {
                return -2;
            }
            return client.status();
        }, getStatus, getNanos);
        report("GET /balances", clients, getStatus, getNanos, getWall);
        assertLoad(getStatus, "GET clients=" + clients);

        awaitCatchUp();
        String balanceCheck = count(postStatus, -1) == 0 ? "OR d.balance <> " + posts : "";
        assertNoRows(writer, """
                SELECT s.account_id, s.balance AS source_balance, d.balance AS dest_balance
                FROM pgledger_accounts s
                JOIN pgledger_accounts d
                  ON d.account_id = regexp_replace(s.account_id, '_S([0-9]+)$', '_D\\1')
                 AND d.balance_type = s.balance_type
                 AND d.currency = s.currency
                WHERE s.account_id LIKE '%s'
                  AND s.balance_type = '%s'
                  AND s.currency = '%s'
                  AND (s.balance + d.balance <> %d %s)
                """.formatted(prefix + "S%", AVAILABLE, USD, posts, balanceCheck), "pair conservation " + prefix);
    }

    private static void assertReadOnlyReplica() throws Exception {
        String code = "ZZ_READER_REJECT";
        String probe = "READER_PROBE_" + suffix();
        client.createAccount(account(probe, AVAILABLE, false));
        awaitCatchUp();
        String nameBefore = queryString(writer,
                "SELECT name FROM pgledger_accounts WHERE account_id = ?", probe);
        try (Connection conn = reader.getConnection()) {
            conn.setAutoCommit(true);
            try {
                conn.setReadOnly(false);
            } catch (SQLException readWrite) {
                assertReadOnly(readWrite);
            }
            SQLException insert = assertThrows(SQLException.class, () -> {
                try (Statement statement = conn.createStatement()) {
                    statement.executeUpdate("""
                            INSERT INTO pgledger_balance_types (code, name, created_at, updated_at)
                            VALUES ('%s', 'no', now(), now())
                            """.formatted(code));
                }
            });
            assertReadOnly(insert);
            SQLException update = assertThrows(SQLException.class, () -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE pgledger_accounts SET name = 'hacked' WHERE account_id = ?")) {
                    ps.setString(1, probe);
                    ps.executeUpdate();
                }
            });
            assertReadOnly(update);
        }
        assertEquals(0L, countWhere(reader, "SELECT count(*) FROM pgledger_balance_types WHERE code = ?", code));
        assertEquals(0L, countWhere(writer, "SELECT count(*) FROM pgledger_balance_types WHERE code = ?", code));
        assertEquals(nameBefore, queryString(writer,
                "SELECT name FROM pgledger_accounts WHERE account_id = ?", probe));
        assertEquals(nameBefore, queryString(reader,
                "SELECT name FROM pgledger_accounts WHERE account_id = ?", probe));
    }

    private static void probeRead(String accountId, AtomicInteger serverErrors, AtomicInteger other) {
        try {
            client.balance(accountId, AVAILABLE, USD);
            noteRead(client.status(), client.role(), serverErrors, other);
            client.journals(0, 20);
            noteRead(client.status(), client.role(), serverErrors, other);
        } catch (PgLedgerClientException e) {
            if (e.status() >= 500 || e.status() < 0) {
                serverErrors.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        }
    }

    private static void noteRead(int status, String role, AtomicInteger serverErrors, AtomicInteger other) {
        if (status >= 500 || status < 0) {
            serverErrors.incrementAndGet();
        } else if (status != 200 || !PgLedgerServer.READER.equals(role)) {
            other.incrementAndGet();
        }
    }

    private static void assertReadOnly(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("read-only") || message.contains("read only") || message.contains("recovery"),
                exception.getMessage());
    }

    private static Account readBalance(String accountId, String balanceType) throws Exception {
        awaitCatchUp();
        BigDecimal onWriter = writerBalance(accountId, balanceType);
        Account onReader = client.balance(accountId, balanceType, USD);
        assertEquals(200, client.status());
        assertEquals(PgLedgerServer.READER, client.role());
        assertNotNull(onReader);
        assertEquals(0, onWriter.compareTo(onReader.balance()), accountId + " " + balanceType);
        return onReader;
    }

    private static void assertInvariants() throws Exception {
        awaitCatchUp();
        assertNoDeadlocks();
        assertNoRows(writer, SUM_BROKEN, "writer money");
        assertNoRows(reader, SUM_BROKEN, "reader money");
        assertNoRows(writer, ROW_BROKEN, "writer version");
        assertNoRows(reader, ROW_BROKEN, "reader version");
        assertNoRows(writer, ORPHAN_TRANSFERS, "writer entries");
        assertNoRows(reader, ORPHAN_TRANSFERS, "reader entries");
        assertNoRows(writer, NEGATIVE, "writer negative");
        assertNoRows(reader, NEGATIVE, "reader negative");
        assertNoRows(writer, ENTRY_CHAIN, "writer entry chain");
        assertNoRows(reader, ENTRY_CHAIN, "reader entry chain");
    }

    private static void assertNoDeadlocks() throws SQLException {
        long deadlocks = queryLong(writer,
                "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()");
        assertEquals(deadlockBaseline, deadlocks, "deadlocks");
    }

    private static void assertLoad(int[] status, String label) {
        int serverErrors = countAtLeast(status, 500);
        int timeouts = count(status, -1);
        int other = 0;
        for (int code : status) {
            if (code != 200 && code != -1 && code < 500) {
                other++;
            }
        }
        assertEquals(0, serverErrors, label + " 5xx, timeouts=" + timeouts);
        assertEquals(0, other, label + " unexpected status, timeouts=" + timeouts);
    }

    private static void report(String op, int clients, int[] status, long[] nanos, long wallNanos) {
        int n = status.length;
        int ok = count(status, 200);
        int serverErrors = countAtLeast(status, 500);
        int timeouts = count(status, -1);
        double seconds = wallNanos / 1_000_000_000.0;
        double tps = seconds == 0.0 ? 0.0 : ok / seconds;
        double errorRate = n == 0 ? 0.0 : (n - ok) / (double) n;
        System.out.println(op
                + " clients=" + clients
                + " n=" + n
                + " tps=" + String.format(Locale.ROOT, "%.1f", tps)
                + " p50=" + percentileMillis(nanos, 50) + "ms"
                + " p95=" + percentileMillis(nanos, 95) + "ms"
                + " p99=" + percentileMillis(nanos, 99) + "ms"
                + " errorRate=" + String.format(Locale.ROOT, "%.4f", errorRate)
                + " 5xx=" + serverErrors
                + " timeouts=" + timeouts);
    }

    private static long percentileMillis(long[] nanos, int percentile) {
        long[] copy = Arrays.copyOf(nanos, nanos.length);
        Arrays.sort(copy);
        int index = (int) Math.ceil(percentile / 100.0 * copy.length) - 1;
        if (index < 0) {
            index = 0;
        }
        return copy[index] / 1_000_000L;
    }

    private static void assertOnly(int[] status, int... allowed) {
        for (int code : status) {
            boolean match = false;
            for (int allow : allowed) {
                if (code == allow) {
                    match = true;
                    break;
                }
            }
            assertTrue(match, "status " + code);
        }
    }

    private static int count(int[] status, int code) {
        int n = 0;
        for (int value : status) {
            if (value == code) {
                n++;
            }
        }
        return n;
    }

    private static int countAtLeast(int[] status, int code) {
        int n = 0;
        for (int value : status) {
            if (value >= code) {
                n++;
            }
        }
        return n;
    }

    private static boolean newerFirst(Transfer previous, Transfer next) {
        int byTime = previous.createdAt().compareTo(next.createdAt());
        if (byTime > 0) {
            return true;
        }
        if (byTime < 0) {
            return false;
        }
        return previous.id().compareTo(next.id()) > 0;
    }

    private static long fill(int threads, int perThread, Attempt attempt, int[] status, long[] nanos) throws Exception {
        Arrays.fill(status, -1);
        long[] first = new long[threads];
        long[] last = new long[threads];
        Arrays.fill(first, Long.MAX_VALUE);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future<?>[threads];
            for (int t = 0; t < threads; t++) {
                int thread = t;
                futures[t] = pool.submit(() -> {
                    barrier.await();
                    for (int seq = 0; seq < perThread; seq++) {
                        int at = thread * perThread + seq;
                        long started = System.nanoTime();
                        if (seq == 0) {
                            first[thread] = started;
                        }
                        int code;
                        try {
                            code = attempt.run(thread, seq);
                        } catch (PgLedgerClientException e) {
                            code = e.status();
                        }
                        nanos[at] = System.nanoTime() - started;
                        status[at] = code;
                        last[thread] = System.nanoTime();
                    }
                    return null;
                });
            }
            for (int i = 0; i < futures.length; i++) {
                try {
                    futures[i].get(5, TimeUnit.MINUTES);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw e;
                } catch (TimeoutException e) {
                    throw new AssertionError("worker timed out", e);
                }
            }
        } catch (BrokenBarrierException e) {
            throw new AssertionError("workers did not start together", e);
        }
        long begin = Long.MAX_VALUE;
        long end = 0L;
        for (int t = 0; t < threads; t++) {
            if (first[t] < begin) {
                begin = first[t];
            }
            if (last[t] > end) {
                end = last[t];
            }
        }
        return end - begin;
    }

    private static void awaitCatchUp() throws Exception {
        long start = System.nanoTime();
        String lsn = queryText(writer, "SELECT pg_current_wal_lsn()::text");
        long boundNs = MAX_LAG_MS * 1_000_000L;
        while (System.nanoTime() - start < boundNs) {
            try (Connection conn = reader.getConnection();
                 PreparedStatement ps = conn.prepareStatement("""
                         SELECT pg_is_in_recovery()
                            AND coalesce(pg_last_wal_replay_lsn() >= ?::pg_lsn, false)
                         """)) {
                ps.setString(1, lsn);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(20L);
        }
        fail("reader did not replay " + lsn + " within " + MAX_LAG_MS + " ms");
    }

    private static void assertNoRows(DataSource dataSource, String sql, String label) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                return;
            }
            StringBuilder text = new StringBuilder(label);
            int shown = 0;
            int columns = rs.getMetaData().getColumnCount();
            do {
                text.append('\n');
                for (int i = 1; i <= columns; i++) {
                    if (i > 1) {
                        text.append(' ');
                    }
                    text.append(rs.getMetaData().getColumnLabel(i)).append('=').append(rs.getString(i));
                }
                shown++;
            } while (shown < 5 && rs.next());
            fail(text.toString());
        }
    }

    private static BigDecimal writerBalance(String accountId, String balanceType) throws SQLException {
        try (Connection conn = writer.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT balance FROM pgledger_accounts
                     WHERE account_id = ? AND balance_type = ? AND currency = ?
                     """)) {
            ps.setString(1, accountId);
            ps.setString(2, balanceType);
            ps.setString(3, USD);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), accountId + " " + balanceType);
                return rs.getBigDecimal(1);
            }
        }
    }

    private static long countWhere(DataSource dataSource, String sql, String arg) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String queryString(DataSource dataSource, String sql, String arg) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            if (arg != null) {
                ps.setString(1, arg);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getString(1);
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

    private static long queryLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            if (!rs.next()) {
                throw new IllegalStateException(sql);
            }
            return rs.getLong(1);
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

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int[] levels() {
        String raw = System.getProperty("pgledger.stress.levels", "50,100,200");
        String[] parts = raw.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].strip());
        }
        return values;
    }

    private static String config(String property, String env, String fallback) {
        String value = System.getProperty(property);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }

    private static PGSimpleDataSource dataSource(String url, String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setConnectTimeout(5);
        return dataSource;
    }

    private static CreateAccount account(String accountId, String balanceType, boolean allowNegative) {
        return new CreateAccount(accountId, balanceType, USD, accountId, allowNegative, true, null);
    }

    private static Posting posting(String fromAccount, String fromType, String toAccount, String toType,
                                   BigDecimal amount) {
        return new Posting(fromAccount, fromType, toAccount, toType, USD, amount, null);
    }

    private static String suffix() {
        return Long.toUnsignedString(System.nanoTime(), 36);
    }

    @FunctionalInterface
    private interface Attempt {
        int run(int thread, int seq);
    }
}
