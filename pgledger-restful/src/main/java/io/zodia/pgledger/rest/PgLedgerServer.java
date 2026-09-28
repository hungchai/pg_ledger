package io.zodia.pgledger.rest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.zodia.pgledger.PgLedger;
import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.store.LedgerJson;
import io.zodia.pgledger.store.LedgerViolation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PgLedgerServer implements AutoCloseable {
    public static final String ROLE_HEADER = "X-Pgledger-Role";
    public static final String WRITER = "writer";
    public static final String READER = "reader";

    private static final int MAX_BODY = 1 << 20;
    private static final int BACKLOG = 1024;
    private static final byte[] NOT_FOUND = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BAD_REQUEST = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER_ERROR = "{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UP = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);

    private final PgLedger ledger;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    private PgLedgerServer(PgLedger ledger, HttpServer server, ExecutorService executor) {
        this.ledger = ledger;
        this.server = server;
        this.executor = executor;
    }

    public static PgLedgerServer start(PgLedger ledger, int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), BACKLOG);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            PgLedgerServer handle = new PgLedgerServer(ledger, server, executor);
            server.createContext("/", handle::handle);
            server.setExecutor(executor);
            server.start();
            return handle;
        } catch (IOException e) {
            throw new IllegalStateException("failed to bind port " + port, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executor.close();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!dispatch(exchange)) {
                send(exchange, 404, null, NOT_FOUND);
            }
        } catch (BadRequest | IllegalArgumentException e) {
            send(exchange, 400, null, BAD_REQUEST);
        } catch (LedgerViolation e) {
            String role = "POST".equals(exchange.getRequestMethod()) ? WRITER : READER;
            send(exchange, 422, role, LedgerJson.writeBytes(Map.of("error", e.getMessage())));
        } catch (RuntimeException e) {
            System.err.println("pgledger " + e.getClass().getName() + ": " + e.getMessage());
            send(exchange, 500, null, SERVER_ERROR);
        } finally {
            exchange.close();
        }
    }

    private boolean dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equals(method) && "/health".equals(path)) {
            send(exchange, 200, null, UP);
            return true;
        }
        String[] parts = new String[6];
        int n = segments(path, parts);
        if (n < 3 || !"api".equals(parts[0]) || !"v1".equals(parts[1])) {
            return false;
        }
        return switch (parts[2]) {
            case "balance-types" -> balanceTypes(exchange, method, n);
            case "accounts" -> accounts(exchange, method, parts, n);
            case "postings" -> postings(exchange, method, n);
            case "balances" -> balances(exchange, method, n);
            case "journals" -> journals(exchange, method, n);
            default -> false;
        };
    }

    private boolean balanceTypes(HttpExchange exchange, String method, int n) throws IOException {
        if (n != 3) {
            return false;
        }
        if ("POST".equals(method)) {
            CreateBalanceType command = read(body(exchange), CreateBalanceType.class);
            send(exchange, 200, WRITER, LedgerJson.writeBytes(ledger.createBalanceType(command)));
            return true;
        }
        if ("GET".equals(method)) {
            send(exchange, 200, READER, LedgerJson.writeBytes(ledger.balanceTypes()));
            return true;
        }
        return false;
    }

    private boolean accounts(HttpExchange exchange, String method, String[] parts, int n) throws IOException {
        if (n == 3 && "POST".equals(method)) {
            CreateAccount command = read(body(exchange), CreateAccount.class);
            send(exchange, 200, WRITER, LedgerJson.writeBytes(ledger.createAccount(command)));
            return true;
        }
        if (n == 5 && "balances".equals(parts[4]) && "GET".equals(method)) {
            send(exchange, 200, READER, LedgerJson.writeBytes(ledger.balances(parts[3])));
            return true;
        }
        return false;
    }

    private boolean postings(HttpExchange exchange, String method, int n) throws IOException {
        if (n != 3 || !"POST".equals(method)) {
            return false;
        }
        Posting posting = read(body(exchange), Posting.class);
        send(exchange, 200, WRITER, LedgerJson.writeBytes(ledger.post(posting)));
        return true;
    }

    private boolean balances(HttpExchange exchange, String method, int n) throws IOException {
        if (n != 3 || !"GET".equals(method)) {
            return false;
        }
        Map<String, String> query = query(exchange);
        String accountId = required(query, "accountId");
        String balanceType = required(query, "balanceType");
        String currency = required(query, "currency");
        Account account = ledger.balance(accountId, balanceType, currency);
        if (account == null) {
            send(exchange, 404, READER, NOT_FOUND);
            return true;
        }
        send(exchange, 200, READER, LedgerJson.writeBytes(account));
        return true;
    }

    private boolean journals(HttpExchange exchange, String method, int n) throws IOException {
        if (n != 3 || !"GET".equals(method)) {
            return false;
        }
        Map<String, String> query = query(exchange);
        int page = queryInt(query, "page", 0, 0, Integer.MAX_VALUE);
        int size = queryInt(query, "size", PgLedger.DEFAULT_PAGE_SIZE, 1, PgLedger.MAX_PAGE_SIZE);
        send(exchange, 200, READER, LedgerJson.writeBytes(ledger.journals(page, size)));
        return true;
    }

    private static int queryInt(Map<String, String> query, String key, int fallback, int min, int max) {
        String raw = query.get(key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        int value = Integer.parseInt(raw);
        if (value < min || value > max) {
            throw new BadRequest();
        }
        return value;
    }

    private static void send(HttpExchange exchange, int status, String role, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (role != null) {
            exchange.getResponseHeaders().set(ROLE_HEADER, role);
        }
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    private static byte[] body(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) {
                throw new BadRequest();
            }
            return bytes;
        }
    }

    private static <T> T read(byte[] json, Class<T> type) {
        if (json.length == 0) {
            throw new BadRequest();
        }
        try {
            T value = LedgerJson.read(json, type);
            if (value == null) {
                throw new BadRequest();
            }
            return value;
        } catch (LedgerJson.JsonReadException e) {
            throw new BadRequest();
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        HashMap<String, String> map = new HashMap<>();
        int start = 0;
        int length = raw.length();
        for (int i = 0; i <= length; i++) {
            if (i == length || raw.charAt(i) == '&') {
                if (i > start) {
                    int eq = -1;
                    for (int j = start; j < i; j++) {
                        if (raw.charAt(j) == '=') {
                            eq = j;
                            break;
                        }
                    }
                    if (eq < 0) {
                        map.put(decode(raw.substring(start, i)), "");
                    } else {
                        map.put(decode(raw.substring(start, eq)), decode(raw.substring(eq + 1, i)));
                    }
                }
                start = i + 1;
            }
        }
        return map;
    }

    private static String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isEmpty()) {
            throw new BadRequest();
        }
        return value;
    }

    private static int segments(String path, String[] out) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return -1;
        }
        int n = 0;
        int start = 1;
        int length = path.length();
        for (int i = 1; i <= length; i++) {
            if (i == length || path.charAt(i) == '/') {
                if (i == start || n == out.length) {
                    return -1;
                }
                out[n++] = path.substring(start, i);
                start = i + 1;
            }
        }
        return n;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new BadRequest();
        }
    }

    private static final class BadRequest extends RuntimeException {
        private BadRequest() {
            super(null, null, false, false);
        }
    }
}
