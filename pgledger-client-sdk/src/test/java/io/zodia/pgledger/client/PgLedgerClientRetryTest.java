package io.zodia.pgledger.client;

import com.sun.net.httpserver.HttpServer;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PgLedgerClientRetryTest {
    @Test
    void retriesServerErrorsAndDoesNotRetryRejection() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/v1/postings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n = hits.incrementAndGet();
            int status = n == 1 ? 500 : 200;
            byte[] body = status == 200
                    ? "{\"id\":\"pglt_1\"}".getBytes(StandardCharsets.UTF_8)
                    : "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set(PgLedgerClient.ROLE_HEADER, "writer");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try (PgLedgerClient client = client(server.getAddress().getPort(), 2)) {
            Transfer result = client.post(posting());
            assertEquals("pglt_1", result.id());
            assertEquals(2, hits.get());
            assertEquals("writer", client.role());
            assertEquals(200, client.status());
        } finally {
            server.stop(0);
        }

        AtomicInteger rejectedHits = new AtomicInteger();
        HttpServer reject = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        reject.createContext("/api/v1/postings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            rejectedHits.incrementAndGet();
            byte[] body = "{\"error\":\"no\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(422, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        reject.start();
        try (PgLedgerClient client = client(reject.getAddress().getPort(), 5)) {
            PgLedgerClientException error = assertThrows(PgLedgerClientException.class, () -> client.post(posting()));
            assertEquals("no", error.getMessage());
            assertEquals(422, error.status());
            assertEquals(1, rejectedHits.get());
            assertEquals(422, client.status());
        } finally {
            reject.stop(0);
        }

        AtomicInteger badHits = new AtomicInteger();
        HttpServer bad = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        bad.createContext("/api/v1/postings", exchange -> {
            exchange.getRequestBody().readAllBytes();
            badHits.incrementAndGet();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        bad.start();
        try (PgLedgerClient client = client(bad.getAddress().getPort(), 3)) {
            PgLedgerClientException error = assertThrows(PgLedgerClientException.class, () -> client.post(posting()));
            assertEquals(400, error.status());
            assertEquals(1, badHits.get());
        } finally {
            bad.stop(0);
        }
    }

    private static PgLedgerClient client(int port, int maxRetries) {
        return new PgLedgerClient(new PgLedgerClientConfig(
                URI.create("http://127.0.0.1:" + port),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                maxRetries));
    }

    private static Posting posting() {
        return new Posting("A", "AVAILABLE", "B", "AVAILABLE", "USD", BigDecimal.ONE, null);
    }
}
