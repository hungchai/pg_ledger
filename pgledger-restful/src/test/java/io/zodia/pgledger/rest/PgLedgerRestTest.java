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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(
        classes = PgLedgerServerMain.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "pgledger.writer-jdbc-url=jdbc:postgresql://localhost:5432/pgledger",
                "pgledger.reader-jdbc-url=jdbc:postgresql://localhost:5433/pgledger",
                "pgledger.jdbc-user=pgledger",
                "pgledger.jdbc-password=pgledger"
        })
class PgLedgerRestTest {
    private static final String WRITER_URL = "jdbc:postgresql://localhost:5432/pgledger";
    private static final String READER_URL = "jdbc:postgresql://localhost:5433/pgledger";
    private static final String DB_USER = "pgledger";
    private static final String DB_PASSWORD = "pgledger";
    private static final long CATCH_UP_NS = 15_000_000_000L;
    private static final AtomicLong IDS = new AtomicLong();

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Test
    void readsHitReplicaAndWritesHitPrimary() throws Exception {
        assertTrue(recovery(false));
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
            client.createBalanceType(new CreateBalanceType(available, "Available", null));
            assertEquals(200, client.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            client.createBalanceType(new CreateBalanceType(locked, "Locked", null));
            nodes.awaitCatchUp();
            List<BalanceType> types = client.balanceTypes();
            assertEquals(PgLedgerServer.READER, client.role());
            assertEquals(1, countCode(types, available));
            assertEquals(1, countCode(types, locked));

            Account companyRow = client.createAccount(account(company, available, "USD", true, true));
            assertEquals(company, companyRow.accountId());
            assertEquals(200, client.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            client.createAccount(account(clientId, available, "USD", false, true));
            client.createAccount(account(clientId, locked, "USD", false, true));

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

            assertNull(client.balance(clientId, available, "GBP"));
            assertEquals(404, client.status());
            assertEquals(PgLedgerServer.READER, client.role());
            http.close();
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
            http.createAccount(account(clientId, type, "USD", false, true));
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

    private static CreateAccount account(String accountId, String balanceType, String currency,
                                         boolean allowNegative, boolean allowPositive) {
        return new CreateAccount(accountId, balanceType, currency, accountId, allowNegative, allowPositive, null, null);
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
        private final DataSource writer;
        private final DataSource reader;

        private Nodes() throws SQLException {
            writer = dataSource(WRITER_URL);
            reader = dataSource(READER_URL);
            if (queryBoolean(writer, "SELECT pg_is_in_recovery()")) {
                throw new IllegalStateException("writer url is a replica");
            }
            if (!queryBoolean(reader, "SELECT pg_is_in_recovery()")) {
                throw new IllegalStateException("reader url is not a replica");
            }
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
