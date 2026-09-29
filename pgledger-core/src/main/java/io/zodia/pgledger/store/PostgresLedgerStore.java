package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.Entry;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Writes are one {@code SELECT pgledger_create_account} or {@code SELECT pgledger_create_transfer}.
 * Locking stays inside those functions. Reads are plain selects.
 */
public final class PostgresLedgerStore implements LedgerStore {
    private static final String[] SCHEMA = {"/db/V001__ledger.sql", "/db/V002__functions.sql"};
    private static final String BALANCE_TYPE_COLUMNS = """
            code, name, description, created_at, updated_at
            """;
    private static final String CREATE_BALANCE_TYPE = "SELECT " + BALANCE_TYPE_COLUMNS
            + " FROM pgledger_create_balance_type(?, ?, ?)";
    private static final String LIST_BALANCE_TYPES = "SELECT " + BALANCE_TYPE_COLUMNS
            + " FROM pgledger_balance_types ORDER BY code";
    private static final String ACCOUNT_COLUMNS = """
            id, account_id, balance_type, name, currency, balance, version,
            allow_negative_balance, allow_positive_balance, metadata::text AS metadata,
            created_at, updated_at, account_class, deleted
            """;
    private static final String CREATE_ACCOUNT = "SELECT " + ACCOUNT_COLUMNS
            + " FROM pgledger_create_account(?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)";
    private static final String ENSURE_BANK_POOL = "SELECT pgledger_ensure_bank_pool(?, ?, ?, ?)";
    private static final String POST_CASH = """
            SELECT id FROM pgledger_post_cash(?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            """;
    private static final String DELETE_ACCOUNT = "SELECT " + ACCOUNT_COLUMNS
            + " FROM pgledger_delete_account(?, ?, ?)";
    private static final String BANK_POSITION = "SELECT pgledger_bank_position(?, ?)";
    private static final String BANK_SHARDS = """
            SELECT
                a.id, a.account_id, a.balance_type, a.name, a.currency, a.balance, a.version,
                a.allow_negative_balance, a.allow_positive_balance, a.metadata::text AS metadata,
                a.created_at, a.updated_at, a.account_class, a.deleted
            FROM pgledger_accounts a
            WHERE a.account_class = 'BANK'
              AND a.balance_type = ?
              AND a.currency = ?
              AND left(a.account_id, char_length('BANK-' || a.currency || '-' || a.balance_type || '-'))
                  = 'BANK-' || a.currency || '-' || a.balance_type || '-'
              AND substring(a.account_id FROM char_length('BANK-' || a.currency || '-' || a.balance_type || '-') + 1) ~ '^[0-9]+$'
            ORDER BY substring(a.account_id FROM char_length('BANK-' || a.currency || '-' || a.balance_type || '-') + 1)::int
            """;
    // The function insert is its own statement. Joining pgledger_entries in that
    // same statement sees a snapshot from before the insert and returns no row.
    private static final String CREATE_TRANSFER = """
            SELECT id FROM pgledger_create_transfer(?, ?, ?, ?, ?, ?, NULL, CAST(? AS jsonb))
            """;
    private static final String LOAD_TRANSFER = """
            SELECT
                t.id AS transfer_id,
                fa.account_id AS from_account_id,
                fa.balance_type AS from_balance_type,
                ta.account_id AS to_account_id,
                ta.balance_type AS to_balance_type,
                fa.currency AS currency,
                t.amount AS transfer_amount,
                t.created_at AS transfer_created_at,
                t.event_at,
                t.metadata::text AS transfer_metadata,
                t.request_id,
                t.biz_type,
                e.id AS entry_id,
                ea.account_id AS entry_account_id,
                ea.balance_type AS entry_balance_type,
                ea.currency AS entry_currency,
                e.amount AS entry_amount,
                e.account_previous_balance,
                e.account_current_balance,
                e.account_version,
                e.created_at AS entry_created_at
            FROM pgledger_transfers t
            JOIN pgledger_accounts fa ON fa.id = t.from_account_id
            JOIN pgledger_accounts ta ON ta.id = t.to_account_id
            JOIN pgledger_entries e ON e.transfer_id = t.id
            JOIN pgledger_accounts ea ON ea.id = e.account_id
            WHERE t.id = ?
            ORDER BY e.id
            """;
    private static final String ONE_BALANCE = "SELECT " + ACCOUNT_COLUMNS + """
             FROM pgledger_accounts
             WHERE account_id = ? AND balance_type = ? AND currency = ?
            """;
    private static final String BALANCES = "SELECT " + ACCOUNT_COLUMNS + """
             FROM pgledger_accounts
             WHERE account_id = ?
             ORDER BY balance_type, currency
            """;
    private static final String JOURNAL_COUNT = "SELECT count(*) FROM pgledger_transfers";
    private static final String JOURNALS = """
            SELECT
                t.id AS transfer_id,
                fa.account_id AS from_account_id,
                fa.balance_type AS from_balance_type,
                ta.account_id AS to_account_id,
                ta.balance_type AS to_balance_type,
                fa.currency AS currency,
                t.amount AS transfer_amount,
                t.created_at AS transfer_created_at,
                t.event_at,
                t.metadata::text AS transfer_metadata,
                t.request_id,
                t.biz_type,
                e.id AS entry_id,
                ea.account_id AS entry_account_id,
                ea.balance_type AS entry_balance_type,
                ea.currency AS entry_currency,
                e.amount AS entry_amount,
                e.account_previous_balance,
                e.account_current_balance,
                e.account_version,
                e.created_at AS entry_created_at
            FROM pgledger_transfers t
            JOIN pgledger_accounts fa ON fa.id = t.from_account_id
            JOIN pgledger_accounts ta ON ta.id = t.to_account_id
            LEFT JOIN pgledger_entries e ON e.transfer_id = t.id
            LEFT JOIN pgledger_accounts ea ON ea.id = e.account_id
            WHERE t.id IN (
                SELECT id FROM pgledger_transfers
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
            )
            ORDER BY t.created_at DESC, t.id DESC, e.id
            """;

    private static final int MIGRATION_ATTEMPTS = 30;

    private final DataSource dataSource;

    public PostgresLedgerStore(DataSource dataSource, boolean migrate) {
        this.dataSource = dataSource;
        if (migrate) {
            migrate(dataSource);
        }
    }

    /**
     * Applies {@code db/V001} and {@code db/V002} on this DataSource.
     * Scripts are {@code IF NOT EXISTS} / {@code CREATE OR REPLACE}.
     * Concurrent startups retry catalog races and ignore "already exists".
     * There is no leader lock.
     */
    public static void migrate(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("writer is required");
        }
        for (int file = 0; file < SCHEMA.length; file++) {
            List<String> statements = SqlScripts.statements(loadSchema(SCHEMA[file]));
            for (int i = 0; i < statements.size(); i++) {
                executeIdempotent(dataSource, statements.get(i));
            }
        }
    }

    @Override
    public BalanceType createBalanceType(CreateBalanceType command) {
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(CREATE_BALANCE_TYPE)) {
                ps.setString(1, command.code());
                ps.setString(2, command.name());
                if (command.description() == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, command.description());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_create_balance_type returned no row");
                    }
                    return balanceType(rs);
                }
            }
        });
    }

    @Override
    public List<BalanceType> balanceTypes() {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(LIST_BALANCE_TYPES);
                 ResultSet rs = ps.executeQuery()) {
                ArrayList<BalanceType> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(balanceType(rs));
                }
                return List.copyOf(rows);
            }
        });
    }

    @Override
    public Account createAccount(CreateAccount command) {
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(CREATE_ACCOUNT)) {
                ps.setString(1, command.accountId());
                ps.setString(2, command.balanceType());
                ps.setString(3, command.name());
                ps.setString(4, command.currency());
                ps.setBoolean(5, flag(command.allowNegativeBalance()));
                ps.setBoolean(6, flag(command.allowPositiveBalance()));
                setJson(ps, 7, command.metadata());
                if (command.accountClass() == null) {
                    ps.setNull(8, Types.VARCHAR);
                } else {
                    ps.setString(8, command.accountClass());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_create_account returned no row");
                    }
                    return account(rs);
                }
            }
        });
    }

    @Override
    public Transfer post(Posting posting) {
        return write(conn -> {
            String transferId;
            try (PreparedStatement ps = conn.prepareStatement(CREATE_TRANSFER)) {
                ps.setString(1, posting.fromAccountId());
                ps.setString(2, posting.fromBalanceType());
                ps.setString(3, posting.toAccountId());
                ps.setString(4, posting.toBalanceType());
                ps.setString(5, posting.currency());
                ps.setBigDecimal(6, posting.amount());
                setJson(ps, 7, posting.metadata());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_create_transfer returned no row");
                    }
                    transferId = rs.getString(1);
                }
            }
            return loadTransfer(conn, transferId);
        });
    }

    @Override
    public int ensureBankPool(String balanceType, String currency, int poolSize, boolean keepExisting) {
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(ENSURE_BANK_POOL)) {
                ps.setString(1, currency);
                ps.setString(2, balanceType);
                ps.setInt(3, poolSize);
                ps.setBoolean(4, keepExisting);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_ensure_bank_pool returned no row");
                    }
                    return rs.getInt(1);
                }
            }
        });
    }

    @Override
    public Transfer postCash(String direction, String requestId, String accountId, String balanceType,
                             String currency, java.math.BigDecimal amount, int poolSize) {
        return write(conn -> {
            String transferId;
            try (PreparedStatement ps = conn.prepareStatement(POST_CASH)) {
                ps.setString(1, requestId);
                ps.setString(2, direction);
                ps.setString(3, accountId);
                ps.setString(4, balanceType);
                ps.setString(5, currency);
                ps.setBigDecimal(6, amount);
                ps.setInt(7, poolSize);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_post_cash returned no row");
                    }
                    transferId = rs.getString(1);
                }
            }
            return loadTransfer(conn, transferId);
        });
    }

    @Override
    public Account deleteAccount(String accountId, String balanceType, String currency) {
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(DELETE_ACCOUNT)) {
                ps.setString(1, accountId);
                ps.setString(2, balanceType);
                ps.setString(3, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_delete_account returned no row");
                    }
                    return account(rs);
                }
            }
        });
    }

    @Override
    public java.math.BigDecimal bankPosition(String balanceType, String currency) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(BANK_POSITION)) {
                ps.setString(1, balanceType);
                ps.setString(2, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_bank_position returned no row");
                    }
                    return rs.getBigDecimal(1);
                }
            }
        });
    }

    @Override
    public List<Account> bankShards(String balanceType, String currency) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(BANK_SHARDS)) {
                ps.setString(1, balanceType);
                ps.setString(2, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    ArrayList<Account> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(account(rs));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    @Override
    public List<Account> balances(String accountId) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(BALANCES)) {
                ps.setString(1, accountId);
                try (ResultSet rs = ps.executeQuery()) {
                    ArrayList<Account> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(account(rs));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    @Override
    public Account balance(String accountId, String balanceType, String currency) {
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(ONE_BALANCE)) {
                ps.setString(1, accountId);
                ps.setString(2, balanceType);
                ps.setString(3, currency);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    return account(rs);
                }
            }
        });
    }

    @Override
    public JournalPage journals(int page, int size) {
        return read(conn -> {
            long total = journalCount(conn);
            long offset = (long) page * (long) size;
            try (PreparedStatement ps = conn.prepareStatement(JOURNALS)) {
                ps.setInt(1, size);
                ps.setLong(2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    LinkedHashMap<String, ArrayList<Entry>> entries = new LinkedHashMap<>();
                    LinkedHashMap<String, Transfer> transfers = new LinkedHashMap<>();
                    while (rs.next()) {
                        String transferId = rs.getString("transfer_id");
                        if (!transfers.containsKey(transferId)) {
                            transfers.put(transferId, transfer(rs, transferId));
                        }
                        String entryId = rs.getString("entry_id");
                        if (entryId != null) {
                            ArrayList<Entry> lines = entries.get(transferId);
                            if (lines == null) {
                                lines = new ArrayList<>(2);
                                entries.put(transferId, lines);
                            }
                            lines.add(entry(rs));
                        }
                    }
                    ArrayList<Transfer> pageRows = new ArrayList<>(transfers.size());
                    for (Transfer transfer : transfers.values()) {
                        List<Entry> lines = entries.get(transfer.id());
                        pageRows.add(withEntries(transfer, lines == null ? List.of() : List.copyOf(lines)));
                    }
                    boolean hasNext = offset + pageRows.size() < total;
                    return new JournalPage(page, size, total, hasNext, List.copyOf(pageRows));
                }
            }
        });
    }

    private static long journalCount(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(JOURNAL_COUNT);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new LedgerException("journal count returned no row");
            }
            return rs.getLong(1);
        }
    }

    @Override
    public void close() {
    }

    private static void executeIdempotent(DataSource dataSource, String sql) {
        SQLException last = null;
        for (int attempt = 1; attempt <= MIGRATION_ATTEMPTS; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 Statement statement = conn.createStatement()) {
                conn.setAutoCommit(true);
                statement.execute(sql);
                return;
            } catch (SQLException e) {
                if (alreadyApplied(e)) {
                    return;
                }
                if (!concurrentDdl(e) || attempt == MIGRATION_ATTEMPTS) {
                    throw new LedgerException("schema migration failed", e);
                }
                last = e;
                LockSupport.parkNanos(backoffNanos(attempt));
            }
        }
        throw new LedgerException("schema migration failed", last);
    }

    private static boolean alreadyApplied(SQLException e) {
        String state = e.getSQLState();
        if ("42P07".equals(state) || "42710".equals(state) || "42723".equals(state)) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains("already exists");
    }

    private static boolean concurrentDdl(SQLException e) {
        String state = e.getSQLState();
        if ("40P01".equals(state) || "40001".equals(state) || "55P03".equals(state) || "23505".equals(state)) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains("tuple concurrently updated");
    }

    private static long backoffNanos(int attempt) {
        long millis = 20L * attempt;
        if (millis > 200L) {
            millis = 200L;
        }
        return TimeUnit.MILLISECONDS.toNanos(millis);
    }

    private <T> T write(Sql<T> work) {
        return inTransaction(false, work);
    }

    private <T> T read(Sql<T> work) {
        return inTransaction(true, work);
    }

    private <T> T inTransaction(boolean readOnly, Sql<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            conn.setReadOnly(readOnly);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T value = work.run(conn);
                conn.commit();
                return value;
            } catch (SQLException e) {
                rollback(conn, e);
                throw translate(e);
            } catch (RuntimeException e) {
                rollback(conn, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new LedgerException("transaction failed", e);
        }
    }

    private static void rollback(Connection conn, Exception cause) {
        try {
            conn.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private static RuntimeException translate(SQLException e) {
        String state = e.getSQLState();
        if ("P0001".equals(state) || "23514".equals(state) || "23505".equals(state)
                || "23502".equals(state) || "23503".equals(state)) {
            return new LedgerViolation(clean(e.getMessage()));
        }
        return new LedgerException(e.getMessage() == null ? "transaction failed" : e.getMessage(), e);
    }

    private static String clean(String message) {
        if (message == null || message.isEmpty()) {
            return "rejected";
        }
        int line = message.indexOf('\n');
        String first = line < 0 ? message : message.substring(0, line);
        if (first.startsWith("ERROR: ")) {
            return first.substring(7);
        }
        return first;
    }

    private static void setJson(PreparedStatement ps, int index, java.util.Map<String, Object> metadata) throws SQLException {
        if (metadata == null) {
            ps.setNull(index, Types.VARCHAR);
        } else {
            ps.setString(index, LedgerJson.writeString(metadata));
        }
    }

    private static boolean flag(Boolean value) {
        return value == null || value.booleanValue();
    }

    private static BalanceType balanceType(ResultSet rs) throws SQLException {
        return new BalanceType(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static Account account(ResultSet rs) throws SQLException {
        return new Account(
                rs.getString("id"),
                rs.getString("account_id"),
                rs.getString("balance_type"),
                rs.getString("name"),
                rs.getString("currency"),
                rs.getBigDecimal("balance"),
                rs.getLong("version"),
                rs.getBoolean("allow_negative_balance"),
                rs.getBoolean("allow_positive_balance"),
                LedgerJson.map(rs.getString("metadata")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("account_class"),
                rs.getBoolean("deleted"));
    }

    private static Transfer loadTransfer(Connection conn, String transferId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(LOAD_TRANSFER)) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                Transfer transfer = oneTransfer(rs);
                if (transfer == null) {
                    throw new LedgerException("pgledger_create_transfer returned no row");
                }
                return transfer;
            }
        }
    }

    private static Transfer oneTransfer(ResultSet rs) throws SQLException {
        Transfer transfer = null;
        ArrayList<Entry> lines = new ArrayList<>(2);
        while (rs.next()) {
            if (transfer == null) {
                transfer = transfer(rs, rs.getString("transfer_id"));
            }
            lines.add(entry(rs));
        }
        if (transfer == null) {
            return null;
        }
        return withEntries(transfer, List.copyOf(lines));
    }

    private static Transfer transfer(ResultSet rs, String id) throws SQLException {
        return new Transfer(
                id,
                rs.getString("from_account_id"),
                rs.getString("from_balance_type"),
                rs.getString("to_account_id"),
                rs.getString("to_balance_type"),
                rs.getString("currency"),
                rs.getBigDecimal("transfer_amount"),
                instant(rs, "transfer_created_at"),
                instant(rs, "event_at"),
                LedgerJson.map(rs.getString("transfer_metadata")),
                rs.getString("request_id"),
                rs.getString("biz_type"),
                List.of());
    }

    private static Transfer withEntries(Transfer transfer, List<Entry> entries) {
        return new Transfer(
                transfer.id(),
                transfer.fromAccountId(),
                transfer.fromBalanceType(),
                transfer.toAccountId(),
                transfer.toBalanceType(),
                transfer.currency(),
                transfer.amount(),
                transfer.createdAt(),
                transfer.eventAt(),
                transfer.metadata(),
                transfer.requestId(),
                transfer.bizType(),
                entries);
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(
                rs.getString("entry_id"),
                rs.getString("entry_account_id"),
                rs.getString("entry_balance_type"),
                rs.getString("entry_currency"),
                rs.getBigDecimal("entry_amount"),
                rs.getBigDecimal("account_previous_balance"),
                rs.getBigDecimal("account_current_balance"),
                rs.getLong("account_version"),
                instant(rs, "entry_created_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String loadSchema(String resource) {
        try (InputStream in = PostgresLedgerStore.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new LedgerException("missing " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LedgerException("schema migration failed", e);
        }
    }

    @FunctionalInterface
    private interface Sql<T> {
        T run(Connection conn) throws SQLException;
    }
}
