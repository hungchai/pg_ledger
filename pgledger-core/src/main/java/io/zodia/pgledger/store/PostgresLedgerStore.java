package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceSnapshot;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.Entry;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.SnapshotMovement;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.read.LedgerReadSql;

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
    private static final String[] SCHEMA = {"/db/V001__ledger.sql", "/db/V002__functions.sql", "/db/V003__shedlock.sql"};
    private static final String BALANCE_TYPE_COLUMNS = """
            id, code, name, description, allow_negative, allow_positive, created_at, updated_at
            """;
    private static final String CREATE_BALANCE_TYPE = "SELECT " + BALANCE_TYPE_COLUMNS
            + " FROM pgledger_create_balance_type(?, ?, ?, ?, ?)";
    private static final String ACCOUNT_COLUMNS = """
            id, account_id, balance_type, name, currency, balance, version,
            allow_negative_balance, allow_positive_balance, metadata::text AS metadata,
            created_at, updated_at, account_class, deleted
            """;
    private static final String CREATE_ACCOUNT = "SELECT " + ACCOUNT_COLUMNS
            + " FROM pgledger_create_account(?, ?, ?, ?, CAST(? AS jsonb), ?)";
    private static final String ENSURE_BANK_POOL = "SELECT pgledger_ensure_bank_pool(?, ?, ?, ?, ?)";
    private static final String CUT_SNAPSHOT = "SELECT pgledger_cut_balance_snapshot(?)";
    private static final String SNAPSHOTS = """
            SELECT s.snapshot_hour, a.account_id, bt.code AS balance_type, c.code AS currency,
                   ac.code AS account_class, s.year, s.month, s.day, s.hour,
                   s.balance, s.previous_balance, s.version, s.deleted
            FROM pgledger_balance_snapshots s
            JOIN pgledger_accounts a ON a.id = s.account_pk
            JOIN pgledger_balance_types bt ON bt.id = s.balance_type_id
            JOIN pgledger_currencies c ON c.id = s.currency_id
            JOIN pgledger_account_classes ac ON ac.id = s.account_class_id
            WHERE s.snapshot_hour = ?
            ORDER BY a.account_id, bt.code, c.code
            """;
    private static final String SNAPSHOT_MOVEMENTS = "SELECT * FROM pgledger_snapshot_movements(?, ?)";
    private static final String DELETE_ACCOUNT = "SELECT " + ACCOUNT_COLUMNS
            + " FROM pgledger_delete_account(?, ?, ?)";
    private static final String ACCOUNT_ID_COLUMNS = """
            a.id, a.account_id, a.balance_type_id, a.name, a.currency_id, a.balance, a.version,
            bt.allow_negative OR ac.code = 'BANK' AS allow_negative_balance,
            bt.allow_positive OR ac.code = 'BANK' AS allow_positive_balance,
            a.metadata::text AS metadata,
            a.created_at, a.updated_at, a.account_class_id, a.deleted
            """;
    private static final String BANK_SHARDS = "SELECT " + ACCOUNT_ID_COLUMNS + """
             FROM pgledger_accounts a
             JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id
             JOIN pgledger_account_classes ac ON ac.id = a.account_class_id
             WHERE a.account_class_id = ?
               AND a.balance_type_id = ?
               AND a.currency_id = ?
               AND left(a.account_id, char_length(?)) = ?
               AND substring(a.account_id FROM char_length(?) + 1) ~ '^[0-9]+$'
             ORDER BY substring(a.account_id FROM char_length(?) + 1)::int
            """;
    // The function insert is its own statement. Joining pgledger_entries in that
    // same statement sees a snapshot from before the insert and returns no row.
    private static final String CREATE_TRANSFER = """
            SELECT id FROM pgledger_create_transfer(?, ?, ?, ?, ?, ?, NULL, ?, ?, ?)
            """;
    private static final String LOAD_TRANSFER = """
            SELECT
                t.id AS transfer_id,
                t.seq AS transfer_seq,
                fa.account_id AS from_account_id,
                fa.balance_type_id AS from_balance_type_id,
                ta.account_id AS to_account_id,
                ta.balance_type_id AS to_balance_type_id,
                fa.currency_id AS currency_id,
                t.amount AS transfer_amount,
                t.created_at AS transfer_created_at,
                t.event_at,
                t.biz_reference,
                t.request_id,
                t.biz_type_id,
                e.id AS entry_id,
                ea.account_id AS entry_account_id,
                ea.balance_type_id AS entry_balance_type_id,
                ea.currency_id AS entry_currency_id,
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
    private static final String LOAD_TRANSFERS_BY_REQUEST = """
            SELECT
                t.id AS transfer_id,
                t.seq AS transfer_seq,
                fa.account_id AS from_account_id,
                fa.balance_type_id AS from_balance_type_id,
                ta.account_id AS to_account_id,
                ta.balance_type_id AS to_balance_type_id,
                fa.currency_id AS currency_id,
                t.amount AS transfer_amount,
                t.created_at AS transfer_created_at,
                t.event_at,
                t.biz_reference,
                t.request_id,
                t.biz_type_id,
                e.id AS entry_id,
                ea.account_id AS entry_account_id,
                ea.balance_type_id AS entry_balance_type_id,
                ea.currency_id AS entry_currency_id,
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
            WHERE t.request_id = ?
            ORDER BY t.id, e.id
            """;
    private static final int MIGRATION_ATTEMPTS = 30;

    private final DataSource dataSource;
    private final RegistryCache registries;
    private final LedgerReadSql.ReadQueryFactory reads;

    public PostgresLedgerStore(DataSource dataSource, boolean migrate, RegistryCache registries) {
        this(dataSource, migrate, registries, LedgerReadSql.queries(LedgerReadSql.factory(dataSource), registries));
    }

    PostgresLedgerStore(DataSource dataSource, boolean migrate, RegistryCache registries,
                        LedgerReadSql.ReadQueryFactory reads) {
        this.dataSource = dataSource;
        this.registries = registries;
        this.reads = reads;
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
                ps.setBoolean(4, command.allowNegative() != null && command.allowNegative().booleanValue());
                ps.setBoolean(5, command.allowPositive() == null || command.allowPositive().booleanValue());
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
        return registries.balanceTypes();
    }

    @Override
    public Account createAccount(CreateAccount command) {
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(CREATE_ACCOUNT)) {
                ps.setString(1, command.accountId());
                ps.setString(2, command.balanceType());
                ps.setString(3, command.name());
                ps.setString(4, command.currency());
                setJson(ps, 5, command.metadata());
                if (command.accountClass() == null) {
                    ps.setNull(6, Types.VARCHAR);
                } else {
                    ps.setString(6, command.accountClass());
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
        List<Transfer> transfers = post(List.of(posting));
        if (transfers.isEmpty()) {
            throw new LedgerException("pgledger_create_transfer returned no row");
        }
        return transfers.get(0);
    }

    @Override
    public List<Transfer> post(List<Posting> legs) {
        if (legs == null || legs.isEmpty()) {
            throw new LedgerException("at least one posting leg is required");
        }
        Posting first = legs.get(0);
        String requestId = first.requestId();
        return write(conn -> {
            StringBuilder sql = new StringBuilder(96 + legs.size() * 48);
            sql.append("SELECT id FROM pgledger_create_transfers(ARRAY[");
            for (int i = 0; i < legs.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append("ROW(?,?,?,?,?,?)::transfer_request");
            }
            sql.append("]::transfer_request[], NULL, ?, ?, ?, ?)");
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int index = 1;
                for (int i = 0; i < legs.size(); i++) {
                    Posting leg = legs.get(i);
                    ps.setString(index++, leg.fromAccountId());
                    ps.setInt(index++, Integer.parseInt(leg.fromBalanceType()));
                    ps.setString(index++, leg.toAccountId());
                    ps.setInt(index++, Integer.parseInt(leg.toBalanceType()));
                    ps.setString(index++, leg.currency());
                    ps.setBigDecimal(index++, leg.amount());
                }
                ps.setString(index++, first.bizReference());
                ps.setString(index++, requestId);
                if (first.bizType() == null || first.bizType().isBlank()) {
                    ps.setNull(index++, Types.VARCHAR);
                } else {
                    ps.setString(index++, first.bizType());
                }
                Boolean autoCreate = first.autoCreate();
                if (autoCreate == null) {
                    ps.setNull(index++, Types.BOOLEAN);
                } else {
                    ps.setBoolean(index++, autoCreate.booleanValue());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_create_transfers returned no row");
                    }
                }
            }
            return loadTransfersByRequest(conn, requestId);
        });
    }

    @Override
    public int ensureBankPool(String balanceTypeCode, int balanceTypeId, String currency, int poolSize, boolean keepExisting) {
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(ENSURE_BANK_POOL)) {
                ps.setString(1, currency);
                ps.setString(2, balanceTypeCode);
                ps.setInt(3, balanceTypeId);
                ps.setInt(4, poolSize);
                ps.setBoolean(5, keepExisting);
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
        return reads.query(reads -> reads.bankPosition(balanceType, currency));
    }

    @Override
    public List<Account> bankShards(String balanceType, String currency) {
        Integer classId = registries.accountClassId("BANK");
        Integer typeId = registries.findBalanceTypeId(balanceType);
        Integer currencyId = registries.currencyId(currency);
        String typeCode = typeId == null ? null : registries.balanceTypeCode(typeId);
        if (classId == null || typeId == null || currencyId == null || typeCode == null || currency == null) {
            return List.of();
        }
        String prefix = "BANK-" + currency.strip() + "-" + typeCode + "-";
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(BANK_SHARDS)) {
                ps.setInt(1, classId);
                ps.setInt(2, typeId);
                ps.setInt(3, currencyId);
                ps.setString(4, prefix);
                ps.setString(5, prefix);
                ps.setString(6, prefix);
                ps.setString(7, prefix);
                try (ResultSet rs = ps.executeQuery()) {
                    ArrayList<Account> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(accountIds(rs));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    @Override
    public List<Account> balances(String accountId) {
        return reads.query(reads -> reads.balances(accountId));
    }

    @Override
    public Account balance(String accountId, String balanceType, String currency) {
        return reads.query(reads -> reads.balance(accountId, balanceType, currency));
    }

    @Override
    public JournalPage journals(int page, int size) {
        return reads.query(reads -> reads.journals(page, size));
    }

    @Override
    public long cutBalanceSnapshot(Instant hour) {
        if (hour == null) {
            throw new LedgerViolation("snapshot hour is required");
        }
        return write(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(CUT_SNAPSHOT)) {
                ps.setTimestamp(1, Timestamp.from(hour));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new LedgerException("pgledger_cut_balance_snapshot returned no row");
                    }
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public List<BalanceSnapshot> snapshots(Instant hour) {
        if (hour == null) {
            throw new LedgerViolation("snapshot hour is required");
        }
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SNAPSHOTS)) {
                ps.setTimestamp(1, Timestamp.from(hour));
                try (ResultSet rs = ps.executeQuery()) {
                    ArrayList<BalanceSnapshot> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new BalanceSnapshot(
                                instant(rs, "snapshot_hour"),
                                rs.getString("account_id"),
                                rs.getString("balance_type"),
                                rs.getString("currency"),
                                rs.getString("account_class"),
                                rs.getInt("year"),
                                rs.getInt("month"),
                                rs.getInt("day"),
                                rs.getInt("hour"),
                                rs.getBigDecimal("balance"),
                                rs.getBigDecimal("previous_balance"),
                                rs.getLong("version"),
                                rs.getBoolean("deleted")));
                    }
                    return List.copyOf(rows);
                }
            }
        });
    }

    @Override
    public List<SnapshotMovement> snapshotMovements(Instant fromHour, Instant toHour) {
        if (fromHour == null) {
            throw new LedgerViolation("from hour is required");
        }
        return read(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(SNAPSHOT_MOVEMENTS)) {
                ps.setTimestamp(1, Timestamp.from(fromHour));
                if (toHour == null) {
                    ps.setNull(2, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(2, Timestamp.from(toHour));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ArrayList<SnapshotMovement> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new SnapshotMovement(
                                rs.getString("balance_type"),
                                rs.getString("currency"),
                                rs.getString("account_class"),
                                rs.getBigDecimal("opening_balance"),
                                rs.getBigDecimal("closing_balance"),
                                rs.getBigDecimal("movement")));
                    }
                    return List.copyOf(rows);
                }
            }
        });
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

    private static BalanceType balanceType(ResultSet rs) throws SQLException {
        return RegistryCache.balanceType(rs);
    }

    private Account accountIds(ResultSet rs) throws SQLException {
        int balanceTypeId = rs.getInt("balance_type_id");
        int currencyId = rs.getInt("currency_id");
        int classId = rs.getInt("account_class_id");
        return new Account(
                rs.getString("id"),
                rs.getString("account_id"),
                codeOrId(registries.balanceTypeCode(balanceTypeId), balanceTypeId),
                rs.getString("name"),
                codeOrId(registries.currencyCode(currencyId), currencyId),
                rs.getBigDecimal("balance"),
                rs.getLong("version"),
                rs.getBoolean("allow_negative_balance"),
                rs.getBoolean("allow_positive_balance"),
                LedgerJson.map(rs.getString("metadata")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                codeOrId(registries.accountClassCode(classId), classId),
                rs.getBoolean("deleted"));
    }

    private static String codeOrId(String code, int id) {
        return code == null ? Integer.toString(id) : code;
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

    private Transfer loadTransfer(Connection conn, String transferId) throws SQLException {
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

    private List<Transfer> loadTransfersByRequest(Connection conn, String requestId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(LOAD_TRANSFERS_BY_REQUEST)) {
            ps.setString(1, requestId);
            try (ResultSet rs = ps.executeQuery()) {
                LinkedHashMap<String, Transfer> transfers = new LinkedHashMap<>();
                LinkedHashMap<String, ArrayList<Entry>> entries = new LinkedHashMap<>();
                while (rs.next()) {
                    String transferId = rs.getString("transfer_id");
                    if (!transfers.containsKey(transferId)) {
                        transfers.put(transferId, transfer(rs, transferId));
                        entries.put(transferId, new ArrayList<>(2));
                    }
                    entries.get(transferId).add(entry(rs));
                }
                if (transfers.isEmpty()) {
                    throw new LedgerException("pgledger_create_transfers returned no row");
                }
                ArrayList<Transfer> page = new ArrayList<>(transfers.size());
                for (Transfer transfer : transfers.values()) {
                    page.add(withEntries(transfer, List.copyOf(entries.get(transfer.id()))));
                }
                return List.copyOf(page);
            }
        }
    }

    private Transfer oneTransfer(ResultSet rs) throws SQLException {
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

    private Transfer transfer(ResultSet rs, String id) throws SQLException {
        int currencyId = rs.getInt("currency_id");
        int bizTypeId = rs.getInt("biz_type_id");
        return new Transfer(
                id,
                rs.getLong("transfer_seq"),
                rs.getString("from_account_id"),
                rs.getInt("from_balance_type_id"),
                rs.getString("to_account_id"),
                rs.getInt("to_balance_type_id"),
                codeOrId(registries.currencyCode(currencyId), currencyId),
                rs.getBigDecimal("transfer_amount"),
                instant(rs, "transfer_created_at"),
                instant(rs, "event_at"),
                rs.getString("request_id"),
                codeOrId(registries.bizTypeCode(bizTypeId), bizTypeId),
                rs.getString("biz_reference"),
                List.of());
    }

    private static Transfer withEntries(Transfer transfer, List<Entry> entries) {
        return new Transfer(
                transfer.id(),
                transfer.seq(),
                transfer.fromAccountId(),
                transfer.fromBalanceType(),
                transfer.toAccountId(),
                transfer.toBalanceType(),
                transfer.currency(),
                transfer.amount(),
                transfer.createdAt(),
                transfer.eventAt(),
                transfer.requestId(),
                transfer.bizType(),
                transfer.bizReference(),
                entries);
    }

    private Entry entry(ResultSet rs) throws SQLException {
        int currencyId = rs.getInt("entry_currency_id");
        return new Entry(
                rs.getString("entry_id"),
                rs.getString("entry_account_id"),
                rs.getInt("entry_balance_type_id"),
                codeOrId(registries.currencyCode(currencyId), currencyId),
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
