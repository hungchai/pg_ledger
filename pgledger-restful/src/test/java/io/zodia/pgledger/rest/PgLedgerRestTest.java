package io.zodia.pgledger.rest;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CashMovement;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.client.PgLedgerClient;
import io.zodia.pgledger.client.PgLedgerClientConfig;
import io.zodia.pgledger.client.PgLedgerClientException;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot REST against one Testcontainers PostgreSQL (writer+reader share it).
 * Schema comes from {@code PostgresLedgerStore.migrate} on context start (V001/V002).
 * Streaming replica remains covered by {@link PgLedgerStressTest} + docker compose.
 */
@SpringBootTest(
        classes = PgLedgerServerMain.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PgLedgerRestTest {
    // Same image major as docker-compose.yml writer (postgres:16). One primary; reads see writes immediately.
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
            .withDatabaseName("pgledger")
            .withUsername("pgledger")
            .withPassword("pgledger")
            .withReuse(false);
    private static final AtomicLong IDS = new AtomicLong();

    static {
        try {
            POSTGRES.start();
        } catch (IllegalStateException e) {
            throw new ExceptionInInitializerError(new IllegalStateException(
                    "Docker is required for PgLedgerRestTest (Testcontainers PostgreSQL). Is the daemon running?", e));
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("pgledger.writer-jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("pgledger.reader-jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("pgledger.jdbc-user", POSTGRES::getUsername);
        registry.add("pgledger.jdbc-password", POSTGRES::getPassword);
    }

    @Test
    void writesAndReadsHitSamePrimary() throws Exception {
        assertFalse(recovery(false));
        assertFalse(recovery(true));
    }

    @Test
    void fiveRoutesUseWriterAndReader() throws Exception {
        try (Nodes nodes = new Nodes();
             PgLedgerClient client = client(port)) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(base(port).resolve("/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertFalse(health.headers().firstValue(PgLedgerServer.ROLE_HEADER).isPresent());

            String prefix = id("REST");
            String available = prefix + "A";
            String locked = prefix + "L";
            String company = prefix + "CO";
            String clientId = prefix + "C";
            BalanceType availableType = client.createBalanceType(new CreateBalanceType(available, "Available", null));
            assertEquals(200, client.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            assertFalse(availableType.allowNegative());
            assertTrue(availableType.allowPositive());
            client.createBalanceType(new CreateBalanceType(locked, "Locked", null));
            String sinkable = prefix + "SINK";
            BalanceType sinkType = client.createBalanceType(
                    new CreateBalanceType(sinkable, "Sink", null, true, false));
            assertTrue(sinkType.allowNegative());
            assertFalse(sinkType.allowPositive());
            nodes.awaitCatchUp();
            List<BalanceType> types = client.balanceTypes();
            assertEquals(PgLedgerServer.READER, client.role());
            assertEquals(1, countCode(types, available));
            assertEquals(1, countCode(types, locked));
            assertEquals(1, countCode(types, sinkable));

            Account companyRow = client.createAccount(
                    new CreateAccount(company, available, "USD", company, null, "BANK"));
            assertEquals(company, companyRow.accountId());
            assertEquals(company, companyRow.accountId());
            assertEquals(200, client.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            client.createAccount(account(clientId, available, "USD"));
            client.createAccount(account(clientId, locked, "USD"));

            Transfer funded = client.post(posting(company, available, clientId, available, "USD", "10"));
            assertEquals(clientId, funded.toAccountId());
            assertEquals(PgLedgerServer.WRITER, client.role());
            nodes.awaitCatchUp();

            List<Account> balances = client.balances(clientId);
            assertEquals(2, balances.size());
            assertEquals(PgLedgerServer.READER, client.role());

            Account availableRow = client.balance(clientId, available, "USD");
            assertEquals(0, new BigDecimal("10").compareTo(availableRow.balance()));
            assertEquals(PgLedgerServer.READER, client.role());

            boolean found = false;
            for (int page = 0; page < 10 && !found; page++) {
                JournalPage journals = client.journals(page, 50);
                assertEquals(PgLedgerServer.READER, client.role());
                if (page == 0) {
                    assertEquals(journals.total() > journals.transfers().size(), journals.hasNext());
                }
                for (int i = 0; i < journals.transfers().size(); i++) {
                    Transfer transfer = journals.transfers().get(i);
                    if (company.equals(transfer.fromAccountId()) && clientId.equals(transfer.toAccountId())) {
                        found = true;
                        assertEquals(0, new BigDecimal("10").compareTo(transfer.amount()));
                        assertEquals(2, transfer.entries().size());
                    }
                }
                if (!journals.hasNext()) {
                    break;
                }
            }
            assertTrue(found);

            PgLedgerClientException rejected = assertThrows(PgLedgerClientException.class,
                    () -> client.post(posting(clientId, available, company, available, "USD", "20")));
            assertEquals(422, rejected.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            assertTrue(rejected.getMessage().contains("does not allow negative balance"));
            assertEquals(0, new BigDecimal("10").compareTo(client.balance(clientId, available, "USD").balance()));

            String sink = prefix + "S";
            client.createAccount(account(sink, sinkable, "USD"));
            PgLedgerClientException noPositive = assertThrows(PgLedgerClientException.class,
                    () -> client.post(posting(company, available, sink, sinkable, "USD", "1")));
            assertEquals(422, noPositive.status());
            assertTrue(noPositive.getMessage().contains("does not allow positive balance"));

            assertNull(client.balance(clientId, available, "GBP"));
            assertEquals(404, client.status());
            assertEquals(PgLedgerServer.READER, client.role());
            http.close();
        }
    }

    /**
     * Two threads post the same requestId at the same time. Exactly one transfer
     * is created and the client is credited once. The loser either returns the
     * same transfer (unique index wait) or 422; both leave the balance at amount.
     */
    @Test
    void concurrentSameRequestIdCreditsOnce() throws Exception {
        try (Nodes nodes = new Nodes();
             PgLedgerClient http = client(port)) {
            String prefix = id("RACE");
            String type = prefix + "A";
            String clientId = prefix + "C";
            http.createBalanceType(new CreateBalanceType(type, "Available", null));
            http.createAccount(account(clientId, type, "USD"));
            http.createAccount(account(prefix + "B", type, "USD"));
            BigDecimal amount = new BigDecimal("30");
            String requestId = prefix + "R1";

            int threads = 2;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<Future<Transfer>> posted = new java.util.ArrayList<>(threads);
            try {
                for (int i = 0; i < threads; i++) {
                    posted.add(pool.submit(() -> {
                        start.await();
                        return http.deposit(new CashMovement(requestId, clientId, type, "USD", amount));
                    }));
                }
                start.countDown();
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (Future<Transfer> future : posted) {
                    ids.add(future.get().id());
                }
                assertEquals(1, ids.size(), "same requestId must map to one transfer");
            } finally {
                pool.shutdownNow();
            }
            nodes.awaitCatchUp();
            assertEquals(0, amount.compareTo(http.balance(clientId, type, "USD").balance()));
            assertEquals(1L, journalCountFor(clientId));
        }
    }

    @Test
    void depositReplayAndDeletedClient() throws Exception {
        try (Nodes nodes = new Nodes();
             PgLedgerClient http = client(port)) {
            String prefix = id("CASH");
            String type = prefix + "A";
            String clientId = prefix + "C";
            http.createBalanceType(new CreateBalanceType(type, "Available", null));
            assertEquals(PgLedgerServer.WRITER, http.role());
            http.createAccount(account(clientId, type, "USD"));
            BigDecimal amount = new BigDecimal("12");
            Transfer first = http.deposit(new CashMovement(prefix + "R1", clientId, type, "USD", amount));
            assertEquals(200, http.status());
            assertEquals(PgLedgerServer.WRITER, http.role());
            assertEquals(clientId, first.toAccountId());
            assertEquals("DEPOSIT", first.bizType());
            assertEquals(prefix + "R1", first.requestId());
            assertTrue(first.fromAccountId().startsWith("BANK-USD-" + type + "-"));
            Transfer again = http.deposit(new CashMovement(prefix + "R1", clientId, type, "USD", amount));
            assertEquals(first.id(), again.id());
            nodes.awaitCatchUp();
            assertEquals(0, amount.compareTo(http.balance(clientId, type, "USD").balance()));

            Account deleted = http.deleteAccount(clientId, type, "USD");
            assertTrue(deleted.deleted());
            assertEquals(PgLedgerServer.WRITER, http.role());
            PgLedgerClientException rejected = assertThrows(PgLedgerClientException.class,
                    () -> http.deposit(new CashMovement(prefix + "R2", clientId, type, "USD", BigDecimal.ONE)));
            assertEquals(422, rejected.status());
            assertTrue(rejected.getMessage().contains("deleted"));
            nodes.awaitCatchUp();
            Account still = http.balance(clientId, type, "USD");
            assertTrue(still.deleted());
            assertEquals(0, amount.compareTo(still.balance()));
        }
    }

    @Test
    void badRequestsAndRemovedRoutes() throws Exception {
        try (HttpClient http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()) {
            URI base = base(port);
            HttpResponse<String> bad = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/postings"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());

            HttpResponse<String> missing = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/accounts/x/freeze"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());

            HttpResponse<String> query = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/balances?accountId=a"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, query.statusCode());

            HttpResponse<String> size = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/journals?size=201"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, size.statusCode());

            HttpResponse<String> defaults = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/journals"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, defaults.statusCode());
            assertEquals(PgLedgerServer.READER, defaults.headers().firstValue(PgLedgerServer.ROLE_HEADER).orElse(null));
            assertTrue(defaults.body().contains("\"size\":50"));
            assertTrue(defaults.body().contains("\"page\":0"));
        }
    }

    private static URI base(int port) {
        return URI.create("http://127.0.0.1:" + port);
    }

    private static PgLedgerClient client(int port) {
        return new PgLedgerClient(new PgLedgerClientConfig(
                URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                0));
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

    private static Posting posting(String fromAccount, String fromType, String toAccount, String toType,
                                   String currency, String amount) {
        return new Posting(fromAccount, fromType, toAccount, toType, currency, new BigDecimal(amount), id("REQ"), null);
    }

    private static String id(String prefix) {
        return prefix + Long.toUnsignedString(IDS.incrementAndGet(), 36)
                + Long.toUnsignedString(System.nanoTime(), 36);
    }

    private boolean recovery(boolean writeRoute) throws SQLException {
        if (writeRoute) {
            try (HintManager hint = HintManager.getInstance()) {
                hint.setWriteRouteOnly();
                return queryRecovery(dataSource);
            }
        }
        return queryRecovery(dataSource);
    }

    private static boolean queryRecovery(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_is_in_recovery()")) {
            if (!rs.next()) {
                throw new IllegalStateException("pg_is_in_recovery returned no row");
            }
            return rs.getBoolean(1);
        }
    }

    private static final class Nodes implements AutoCloseable {
        private Nodes() {
        }

        private void awaitCatchUp() {
            // Single primary: writer and reader share one JDBC URL, so writes are already visible.
        }

        @Override
        public void close() {
        }
    }

    private static PGSimpleDataSource dataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setConnectTimeout(5);
        return dataSource;
    }

    /**
     * Transfers involving the client, read from the writer so no replica lag.
     */
    private long journalCountFor(String clientId) throws SQLException {
        try (Connection conn = dataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT count(DISTINCT t.id)
                     FROM pgledger_transfers t
                     JOIN pgledger_accounts a ON a.id IN (t.from_account_id, t.to_account_id)
                     WHERE a.account_id = ?
                     """)) {
            ps.setString(1, clientId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("journal count returned no row");
                }
                return rs.getLong(1);
            }
        }
    }
}
