package io.zodia.pgledger.rest;

import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.client.PgLedgerClient;
import io.zodia.pgledger.client.PgLedgerClientConfig;
import io.zodia.pgledger.client.PgLedgerClientException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgLedgerRestTest {
    @Test
    void fiveRoutesUseWriterAndReader() throws Exception {
        try (PgLedger ledger = PgLedger.inMemory();
             PgLedgerServer server = PgLedgerServer.start(ledger, 0);
             PgLedgerClient client = client(server.port())) {
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> health = http.send(HttpRequest.newBuilder(base(server).resolve("/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode());
            assertFalse(health.headers().firstValue(PgLedgerServer.ROLE_HEADER).isPresent());

            client.createBalanceType(new CreateBalanceType("AVAILABLE", "Available", null));
            assertEquals(200, client.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            client.createBalanceType(new CreateBalanceType("LOCKED", "Locked", null));
            assertEquals(2, client.balanceTypes().size());
            assertEquals(PgLedgerServer.READER, client.role());

            Account company = client.createAccount(account("COMPANY", "AVAILABLE", "USD", true, true));
            assertEquals("COMPANY", company.accountId());
            assertEquals(200, client.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            client.createAccount(account("CLIENT_ACC_001", "AVAILABLE", "USD", false, true));
            client.createAccount(account("CLIENT_ACC_001", "LOCKED", "USD", false, true));

            Transfer funded = client.post(posting("COMPANY", "AVAILABLE", "CLIENT_ACC_001", "AVAILABLE", "USD", "10"));
            assertEquals("CLIENT_ACC_001", funded.toAccountId());
            assertEquals(PgLedgerServer.WRITER, client.role());

            List<Account> balances = client.balances("CLIENT_ACC_001");
            assertEquals(2, balances.size());
            assertEquals(PgLedgerServer.READER, client.role());

            Account available = client.balance("CLIENT_ACC_001", "AVAILABLE", "USD");
            assertEquals(0, new BigDecimal("10").compareTo(available.balance()));
            assertEquals(PgLedgerServer.READER, client.role());

            JournalPage journals = client.journals(0, 50);
            assertEquals(1L, journals.total());
            assertFalse(journals.hasNext());
            assertEquals(1, journals.transfers().size());
            assertEquals(PgLedgerServer.READER, client.role());

            PgLedgerClientException rejected = assertThrows(PgLedgerClientException.class,
                    () -> client.post(posting("CLIENT_ACC_001", "AVAILABLE", "COMPANY", "AVAILABLE", "USD", "20")));
            assertEquals(422, rejected.status());
            assertEquals(PgLedgerServer.WRITER, client.role());
            assertTrue(rejected.getMessage().contains("does not allow negative balance"));
            assertEquals(0, new BigDecimal("10").compareTo(client.balance("CLIENT_ACC_001", "AVAILABLE", "USD").balance()));

            assertNull(client.balance("CLIENT_ACC_001", "AVAILABLE", "GBP"));
            assertEquals(404, client.status());
            assertEquals(PgLedgerServer.READER, client.role());
            http.close();
        }
    }

    @Test
    void badRequestsAndRemovedRoutes() throws Exception {
        try (PgLedger ledger = PgLedger.inMemory();
             PgLedgerServer server = PgLedgerServer.start(ledger, 0)) {
            HttpClient http = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            URI base = base(server);
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
            http.close();
        }
    }

    private static URI base(PgLedgerServer server) {
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private static PgLedgerClient client(int port) {
        return new PgLedgerClient(new PgLedgerClientConfig(
                URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                0));
    }

    private static CreateAccount account(String accountId, String balanceType, String currency,
                                         boolean allowNegative, boolean allowPositive) {
        return new CreateAccount(accountId, balanceType, currency, accountId, allowNegative, allowPositive, null);
    }

    private static Posting posting(String fromAccount, String fromType, String toAccount, String toType,
                                   String currency, String amount) {
        return new Posting(fromAccount, fromType, toAccount, toType, currency, new BigDecimal(amount), null);
    }
}
