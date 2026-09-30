package io.zodia.pgledger.client;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CashMovement;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.DeleteAccount;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.PostingBatch;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerJson;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the five ledger routes. Retries I/O failures and HTTP 5xx.
 * HTTP 422 is a business rejection and is never retried.
 */
public final class PgLedgerClient implements AutoCloseable {
    static final String ROLE_HEADER = "X-Pgledger-Role";

    private final HttpClient http;
    private final String baseUrl;
    private final Duration readTimeout;
    private final int attempts;
    private final ThreadLocal<String> role = new ThreadLocal<>();
    /** Mutable holder avoids boxing an {@code Integer} per response. Unset is -1. */
    private final ThreadLocal<int[]> statusCode = ThreadLocal.withInitial(() -> new int[]{-1});

    public PgLedgerClient(PgLedgerClientConfig config) {
        String base = config.baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.readTimeout = config.readTimeout();
        this.attempts = config.maxRetries() + 1;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .build();
    }

    /** {@code X-Pgledger-Role} from the latest response on this thread. */
    public String role() {
        return role.get();
    }

    /** HTTP status from the latest response on this thread, or -1 when the call failed before a response. */
    public int status() {
        return statusCode.get()[0];
    }

    public BalanceType createBalanceType(CreateBalanceType command) {
        return read(ok(exchange("POST", "/api/v1/balance-types", LedgerJson.writeBytes(command))), BalanceType.class);
    }

    public List<BalanceType> balanceTypes() {
        return readList(ok(exchange("GET", "/api/v1/balance-types", null)), BalanceType.class);
    }

    public Account createAccount(CreateAccount command) {
        return read(ok(exchange("POST", "/api/v1/accounts", LedgerJson.writeBytes(command))), Account.class);
    }

    public Transfer post(Posting posting) {
        return read(ok(exchange("POST", "/api/v1/postings", LedgerJson.writeBytes(posting))), Transfer.class);
    }

    public List<Transfer> post(PostingBatch batch) {
        return readList(ok(exchange("POST", "/api/v1/postings", LedgerJson.writeBytes(batch))), Transfer.class);
    }

    public Transfer deposit(CashMovement movement) {
        return read(ok(exchange("POST", "/api/v1/deposits", LedgerJson.writeBytes(movement))), Transfer.class);
    }

    public Transfer withdraw(CashMovement movement) {
        return read(ok(exchange("POST", "/api/v1/withdrawals", LedgerJson.writeBytes(movement))), Transfer.class);
    }

    public Account deleteAccount(String accountId, String balanceType, String currency) {
        DeleteAccount command = new DeleteAccount(accountId, balanceType, currency);
        return read(ok(exchange("POST", "/api/v1/accounts/delete", LedgerJson.writeBytes(command))), Account.class);
    }

    public List<Account> balances(String accountId) {
        return readList(ok(exchange("GET", "/api/v1/accounts/" + encode(accountId) + "/balances", null)), Account.class);
    }

    public Account balance(String accountId, String balanceType, String currency) {
        String path = "/api/v1/balances?" + query(
                "accountId", accountId,
                "balanceType", balanceType,
                "currency", currency);
        Exchange exchange = exchange("GET", path, null);
        if (exchange.status == 404) {
            return null;
        }
        return read(ok(exchange), Account.class);
    }

    public JournalPage journals(int page, int size) {
        String path = "/api/v1/journals?" + query("page", Integer.toString(page), "size", Integer.toString(size));
        return read(ok(exchange("GET", path, null)), JournalPage.class);
    }

    @Override
    public void close() {
        role.remove();
        statusCode.remove();
        http.close();
    }

    private byte[] ok(Exchange exchange) {
        if (exchange.status == 200) {
            return exchange.body;
        }
        if (exchange.status == 422) {
            throw new PgLedgerClientException(errorText(exchange.body), exchange.status, null);
        }
        throw new PgLedgerClientException("HTTP " + exchange.status, exchange.status, null);
    }

    private Exchange exchange(String method, String path, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header("Accept", "application/json");
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpRequest request = builder.build();
        IOException lastIo = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 500 && status < 600 && attempt + 1 < attempts) {
                    continue;
                }
                statusCode.get()[0] = status;
                String header = response.headers().firstValue(ROLE_HEADER).orElse(null);
                if (header == null) {
                    role.remove();
                } else {
                    role.set(header);
                }
                byte[] responseBody = response.body();
                return new Exchange(status, responseBody == null ? new byte[0] : responseBody);
            } catch (IOException e) {
                lastIo = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                role.remove();
                statusCode.remove();
                throw new PgLedgerClientException("interrupted", -1, e);
            }
        }
        role.remove();
        statusCode.remove();
        throw new PgLedgerClientException("request failed", -1, lastIo);
    }

    private static String errorText(byte[] body) {
        try {
            Map<?, ?> map = LedgerJson.read(body, Map.class);
            if (map != null) {
                Object error = map.get("error");
                if (error != null) {
                    return error.toString();
                }
            }
        } catch (RuntimeException ignored) {
            return "rejected";
        }
        return "rejected";
    }

    private static <T> T read(byte[] json, Class<T> type) {
        try {
            return LedgerJson.read(json, type);
        } catch (LedgerJson.JsonReadException e) {
            throw new PgLedgerClientException("invalid json", -1, e);
        }
    }

    private static <T> List<T> readList(byte[] json, Class<T> type) {
        try {
            return LedgerJson.list(json, type);
        } catch (LedgerJson.JsonReadException e) {
            throw new PgLedgerClientException("invalid json", -1, e);
        }
    }

    private static String query(String... pairs) {
        StringBuilder text = new StringBuilder(96);
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                text.append('&');
            }
            text.append(encode(pairs[i])).append('=').append(encode(pairs[i + 1]));
        }
        return text.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record Exchange(int status, byte[] body) {
    }
}
