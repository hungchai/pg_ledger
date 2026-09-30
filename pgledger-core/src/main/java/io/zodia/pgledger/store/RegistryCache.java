package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.BalanceType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-process copy of the four registries. Loaded at startup and replaced every
 * {@link #REFRESH_SECONDS}. Request threads read this snapshot and do not query
 * the registry tables. A row inserted by another session appears on the next refresh.
 */
public final class RegistryCache implements AutoCloseable {
    public static final int REFRESH_SECONDS = 60;

    private static final String ACCOUNT_CLASSES = """
            SELECT id, code FROM pgledger_account_classes
            """;
    private static final String BALANCE_TYPES = """
            SELECT id, code, name, description, allow_negative, allow_positive, created_at, updated_at
            FROM pgledger_balance_types
            """;
    private static final String BIZ_TYPES = """
            SELECT id, code, name FROM pgledger_biz_types
            """;
    private static final String CURRENCIES = """
            SELECT id, code, scale FROM pgledger_currencies
            """;

    private final DataSource dataSource;
    private final ScheduledExecutorService scheduler;
    private volatile Snapshot snapshot;

    public RegistryCache(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("writer is required");
        }
        this.dataSource = dataSource;
        this.snapshot = load();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "pgledger-registry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::refreshQuietly, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    public void refresh() {
        snapshot = load();
    }

    public void remember(BalanceType type) {
        if (type == null) {
            return;
        }
        snapshot = snapshot.withBalanceType(type);
    }

    public List<BalanceType> balanceTypes() {
        return snapshot.balanceTypes();
    }

    /**
     * A numeric argument is the id and is passed through. A code is resolved here.
     * An unknown code fails. An id that is not in the snapshot still passes through.
     */
    public Integer findBalanceTypeId(String raw) {
        String text = strip(raw);
        if (text == null) {
            return null;
        }
        Integer parsed = parseId(text);
        if (parsed != null) {
            return parsed;
        }
        BalanceType found = snapshot.balanceTypeByCode(text);
        return found == null ? null : found.id();
    }

    public int balanceTypeId(String raw) {
        String text = strip(raw);
        if (text == null) {
            throw new LedgerViolation("balance type not found");
        }
        Integer parsed = parseId(text);
        if (parsed != null) {
            return parsed;
        }
        BalanceType found = snapshot.balanceTypeByCode(text);
        if (found == null) {
            throw new LedgerViolation("balance type not found");
        }
        return found.id();
    }

    public String balanceTypeCode(int id) {
        BalanceType found = snapshot.balanceTypeById(id);
        return found == null ? null : found.code();
    }

    public BalanceType balanceTypeByCode(String code) {
        String text = strip(code);
        return text == null ? null : snapshot.balanceTypeByCode(text);
    }

    public Integer currencyId(String code) {
        String text = strip(code);
        return text == null ? null : snapshot.currencyId(text);
    }

    public String currencyCode(int id) {
        return snapshot.currencyCode(id);
    }

    public void requireCurrency(String code) {
        if (currencyId(code) == null) {
            throw new LedgerViolation("currency not found");
        }
    }

    public void requireBizType(String code) {
        String text = strip(code);
        if (text == null || snapshot.bizTypeId(text.toUpperCase()) == null) {
            throw new LedgerViolation("biz type not found");
        }
    }

    public String bizTypeCode(int id) {
        return snapshot.bizTypeCode(id);
    }

    public Integer accountClassId(String code) {
        String text = strip(code);
        return text == null ? null : snapshot.accountClassId(text.toUpperCase());
    }

    public String accountClassCode(int id) {
        return snapshot.accountClassCode(id);
    }

    /**
     * Blank becomes CLIENT. Any other value must already be in the snapshot.
     */
    public String requireAccountClass(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CLIENT";
        }
        String code = raw.strip().toUpperCase();
        if (accountClassId(code) == null) {
            throw new LedgerViolation("account_class not found");
        }
        return code;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (RuntimeException ignored) {
            // Keep the last good snapshot. The next interval tries again.
        }
    }

    private Snapshot load() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            conn.setReadOnly(true);
            return new Snapshot(
                    readClasses(conn),
                    readBalanceTypes(conn),
                    readBizTypes(conn),
                    readCurrencies(conn));
        } catch (SQLException e) {
            throw new LedgerException("registry refresh failed", e);
        }
    }

    private static List<Coded> readClasses(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(ACCOUNT_CLASSES);
             ResultSet rs = ps.executeQuery()) {
            ArrayList<Coded> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Coded(rs.getInt("id"), rs.getString("code")));
            }
            return List.copyOf(rows);
        }
    }

    private static List<BalanceType> readBalanceTypes(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(BALANCE_TYPES);
             ResultSet rs = ps.executeQuery()) {
            ArrayList<BalanceType> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(balanceType(rs));
            }
            return List.copyOf(rows);
        }
    }

    private static List<Coded> readBizTypes(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(BIZ_TYPES);
             ResultSet rs = ps.executeQuery()) {
            ArrayList<Coded> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Coded(rs.getInt("id"), rs.getString("code")));
            }
            return List.copyOf(rows);
        }
    }

    private static List<Coded> readCurrencies(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(CURRENCIES);
             ResultSet rs = ps.executeQuery()) {
            ArrayList<Coded> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new Coded(rs.getInt("id"), rs.getString("code")));
            }
            return List.copyOf(rows);
        }
    }

    static BalanceType balanceType(ResultSet rs) throws SQLException {
        return new BalanceType(
                rs.getInt("id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("allow_negative"),
                rs.getBoolean("allow_positive"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String strip(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        return text.isEmpty() ? null : text;
    }

    private static Integer parseId(String text) {
        int start = 0;
        if (text.charAt(0) == '+') {
            start = 1;
        }
        if (start >= text.length()) {
            return null;
        }
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
        }
        try {
            return Integer.valueOf(text.substring(start));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Coded(int id, String code) {
    }

    private record Snapshot(
            List<Coded> accountClasses,
            List<BalanceType> balanceTypes,
            List<Coded> bizTypes,
            List<Coded> currencies) {

        private Snapshot withBalanceType(BalanceType type) {
            ArrayList<BalanceType> rows = new ArrayList<>(balanceTypes.size() + 1);
            for (int i = 0; i < balanceTypes.size(); i++) {
                BalanceType existing = balanceTypes.get(i);
                if (existing.id() != type.id() && !type.code().equals(existing.code())) {
                    rows.add(existing);
                }
            }
            rows.add(type);
            return new Snapshot(accountClasses, List.copyOf(rows), bizTypes, currencies);
        }

        private BalanceType balanceTypeByCode(String code) {
            for (int i = 0; i < balanceTypes.size(); i++) {
                if (code.equals(balanceTypes.get(i).code())) {
                    return balanceTypes.get(i);
                }
            }
            return null;
        }

        private BalanceType balanceTypeById(int id) {
            for (int i = 0; i < balanceTypes.size(); i++) {
                if (balanceTypes.get(i).id() == id) {
                    return balanceTypes.get(i);
                }
            }
            return null;
        }

        private Integer currencyId(String code) {
            return idOf(currencies, code);
        }

        private String currencyCode(int id) {
            return codeOf(currencies, id);
        }

        private Integer bizTypeId(String code) {
            return idOf(bizTypes, code);
        }

        private String bizTypeCode(int id) {
            return codeOf(bizTypes, id);
        }

        private Integer accountClassId(String code) {
            return idOf(accountClasses, code);
        }

        private String accountClassCode(int id) {
            return codeOf(accountClasses, id);
        }

        private static Integer idOf(List<Coded> rows, String code) {
            for (int i = 0; i < rows.size(); i++) {
                if (code.equals(rows.get(i).code())) {
                    return rows.get(i).id();
                }
            }
            return null;
        }

        private static String codeOf(List<Coded> rows, int id) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).id() == id) {
                    return rows.get(i).code();
                }
            }
            return null;
        }
    }
}
