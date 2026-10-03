package io.zodia.pgledger;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceSnapshot;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CashMovement;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.PostingBatch;
import io.zodia.pgledger.api.LedgerApi.PostingLeg;
import io.zodia.pgledger.api.LedgerApi.SnapshotMovement;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerStore;
import io.zodia.pgledger.store.LedgerViolation;
import io.zodia.pgledger.store.PostgresLedgerStore;
import io.zodia.pgledger.store.RegistryCache;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One balance row is {@code (account_id, balance_type, currency)}.
 * {@code pgledger_accounts.id} stays the internal primary key that transfers and
 * entries reference, so a transfer still points at one balance row.
 * {@code account_id} is the caller's business id.
 * {@code GET /accounts/{id}/balances} lists every balance type and currency for that id.
 *
 * <p>Writes are one call to {@code pgledger_create_account} or
 * {@code pgledger_create_transfer}. Those functions lock balance rows in sorted
 * internal id order. Reads are plain selects on the reader database.
 */
public final class PgLedger implements AutoCloseable {
    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int DEFAULT_BANK_POOL_SIZE = 8;
    public static final int MAX_BANK_POOL_SIZE = 1024;

    private final LedgerStore writer;
    private final LedgerStore reader;
    private final RegistryCache registries;
    private final int bankPoolSize;

    private PgLedger(LedgerStore writer, LedgerStore reader, RegistryCache registries, int bankPoolSize) {
        if (bankPoolSize < 1 || bankPoolSize > MAX_BANK_POOL_SIZE) {
            throw new IllegalArgumentException("bank pool size");
        }
        this.writer = writer;
        this.reader = reader;
        this.registries = registries;
        this.bankPoolSize = bankPoolSize;
    }

    /**
     * The registry snapshot backing id/code resolution. Shared, read-only view.
     */
    public RegistryCache registryCache() {
        return registries;
    }

    /**
     * Writes go to {@code writer}. Balance and journal queries go to {@code reader}.
     * The reader URL is required and is never copied from the writer.
     */
    public static PgLedger postgres(DataSource writer, DataSource reader) {
        if (writer == null || reader == null) {
            throw new IllegalArgumentException("writer and reader are required");
        }
        return postgres(writer, reader, DEFAULT_BANK_POOL_SIZE);
    }

    public static PgLedger postgres(DataSource writer, DataSource reader, int bankPoolSize) {
        if (writer == null || reader == null) {
            throw new IllegalArgumentException("writer and reader are required");
        }
        PostgresLedgerStore.migrate(writer);
        RegistryCache registries = new RegistryCache(reader);
        PostgresLedgerStore writerStore = new PostgresLedgerStore(writer, false, registries);
        PostgresLedgerStore readerStore = new PostgresLedgerStore(reader, false, registries);
        return new PgLedger(writerStore, readerStore, registries, bankPoolSize);
    }

    /**
     * One JDBC DataSource for reads and writes. Schema migration is not run.
     * Callers that use read/write splitting must force the write route around
     * {@link #createBalanceType}, {@link #createAccount}, and {@link #post}
     * before this method borrows a connection.
     */
    public static PgLedger routed(DataSource dataSource) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is required");
        }
        return routed(dataSource, DEFAULT_BANK_POOL_SIZE);
    }

    public static PgLedger routed(DataSource dataSource, int bankPoolSize) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource is required");
        }
        RegistryCache registries = new RegistryCache(dataSource);
        PostgresLedgerStore store = new PostgresLedgerStore(dataSource, false, registries);
        return new PgLedger(store, store, registries, bankPoolSize);
    }

    public static PgLedger postgres(String writerUrl, String readerUrl, String user, String password) {
        if (writerUrl == null || writerUrl.isBlank() || readerUrl == null || readerUrl.isBlank()) {
            throw new IllegalArgumentException("writer and reader JDBC URLs are required");
        }
        return postgres(dataSource(writerUrl, user, password), dataSource(readerUrl, user, password));
    }

    public BalanceType createBalanceType(CreateBalanceType command) {
        if (command == null || blank(command.code())) {
            throw new LedgerViolation("balance_type code is required");
        }
        String code = command.code().strip();
        String name = command.name() == null || command.name().isBlank() ? code : command.name().strip();
        String description = command.description() == null || command.description().isBlank()
                ? null
                : command.description().strip();
        BalanceType created = writer.createBalanceType(new CreateBalanceType(code, name, description,
                command.allowNegative(), command.allowPositive()));
        registries.remember(created);
        return created;
    }

    public void refreshRegistries() {
        registries.refresh();
    }

    public List<BalanceType> balanceTypes() {
        return reader.balanceTypes();
    }

    public Account createAccount(CreateAccount command) {
        if (command == null) {
            throw new LedgerViolation("account_id, balance_type, and currency are required");
        }
        String accountId = required(command.accountId());
        String balanceType = required(command.balanceType());
        String currency = required(command.currency());
        if (registries.balanceTypeByCode(balanceType) == null) {
            throw new LedgerViolation("balance type not found");
        }
        registries.requireCurrency(currency);
        String name = command.name() == null || command.name().isBlank() ? accountId : command.name().strip();
        return writer.createAccount(new CreateAccount(
                accountId,
                balanceType,
                currency,
                name,
                command.metadata(),
                registries.requireAccountClass(command.accountClass())));
    }

    public int ensureBankPool(String balanceType, String currency, int poolSize) {
        int typeId = registries.balanceTypeId(required(balanceType));
        String typeCode = registries.balanceTypeCode(typeId);
        if (typeCode == null) {
            throw new LedgerViolation("balance type not found");
        }
        registries.requireCurrency(required(currency));
        return writer.ensureBankPool(typeCode, typeId, required(currency), poolSize(poolSize), false);
    }

    public Transfer deposit(CashMovement movement) {
        return moveViaBank(movement, "BANK", null, "DEPOSIT");
    }

    public Transfer withdraw(CashMovement movement) {
        return moveViaBank(movement, null, "BANK", "WITHDRAWAL");
    }

    public Account deleteAccount(String accountId, String balanceType, String currency) {
        return writer.deleteAccount(required(accountId), required(balanceType), required(currency));
    }

    public BigDecimal bankPosition(String balanceType, String currency) {
        BigDecimal position = reader.bankPosition(required(balanceType), required(currency));
        return position == null ? BigDecimal.ZERO : position;
    }

    public List<Account> bankShards(String balanceType, String currency) {
        return reader.bankShards(required(balanceType), required(currency));
    }

    public Transfer post(Posting posting) {
        if (posting == null) {
            throw new LedgerViolation("account_id, balance_type, and currency are required");
        }
        List<Transfer> transfers = post(new PostingBatch(
                posting.requestId(),
                posting.bizReference(),
                posting.bizType(),
                List.of(new PostingLeg(
                        posting.fromAccountId(),
                        posting.fromBalanceType(),
                        posting.toAccountId(),
                        posting.toBalanceType(),
                        posting.currency(),
                        posting.amount())),
                posting.autoCreate()));
        return transfers.get(0);
    }

    /**
     * Atomic multi-leg posting (RFQ). One {@code requestId}, all legs commit or none.
     */
    public List<Transfer> post(PostingBatch batch) {
        if (batch == null || batch.legs() == null || batch.legs().isEmpty()) {
            throw new LedgerViolation("at least one posting leg is required");
        }
        if (blank(batch.requestId())) {
            throw new LedgerViolation("request_id is required");
        }
        String requestId = batch.requestId().strip();
        String bizReference = batch.bizReference() == null || batch.bizReference().isBlank()
                ? null
                : batch.bizReference().strip();
        String bizType = batch.bizType() == null || batch.bizType().isBlank()
                ? null
                : batch.bizType().strip();
        if (bizType != null) {
            registries.requireBizType(bizType);
        }
        ArrayList<Posting> resolved = new ArrayList<>(batch.legs().size());
        for (int i = 0; i < batch.legs().size(); i++) {
            PostingLeg leg = batch.legs().get(i);
            if (leg == null
                    || blank(leg.fromAccountId())
                    || blank(leg.fromBalanceType())
                    || blank(leg.toAccountId())
                    || blank(leg.toBalanceType())
                    || blank(leg.currency())) {
                throw new LedgerViolation("account_id, balance_type, and currency are required");
            }
            BigDecimal amount = leg.amount();
            if (amount == null || amount.signum() <= 0) {
                throw new LedgerViolation("Amount (" + (amount == null ? "null" : amount.toPlainString()) + ") must be positive");
            }
            registries.requireCurrency(leg.currency());
            int fromType = registries.balanceTypeId(leg.fromBalanceType());
            int toType = registries.balanceTypeId(leg.toBalanceType());
            resolved.add(new Posting(
                    leg.fromAccountId().strip(),
                    Integer.toString(fromType),
                    leg.toAccountId().strip(),
                    Integer.toString(toType),
                    leg.currency().strip(),
                    amount,
                    requestId,
                    bizReference,
                    bizType,
                    batch.autoCreate()));
        }
        return writer.post(List.copyOf(resolved));
    }

    public List<Account> balances(String accountId) {
        return reader.balances(accountId);
    }

    public Account balance(String accountId, String balanceType, String currency) {
        return reader.balance(accountId, balanceType, currency);
    }

    public JournalPage journals(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page and size");
        }
        return reader.journals(page, size);
    }

    /** Cuts the hourly snapshot on the writer (must be a full UTC hour). */
    public long cutBalanceSnapshot(Instant hour) {
        if (hour == null) {
            throw new LedgerViolation("snapshot hour is required");
        }
        if (hour.getEpochSecond() % 3600 != 0) {
            throw new LedgerViolation("snapshot hour must be a full UTC hour (minute and second 0)");
        }
        return writer.cutBalanceSnapshot(hour);
    }

    /** Snapshot rows for one hour, read from the reader. */
    public List<BalanceSnapshot> snapshots(Instant hour) {
        if (hour == null) {
            throw new LedgerViolation("snapshot hour is required");
        }
        return reader.snapshots(hour);
    }

    /** Movement between snapshot hours; toHour null = latest, from the reader. */
    public List<SnapshotMovement> snapshotMovements(Instant fromHour, Instant toHour) {
        if (fromHour == null) {
            throw new LedgerViolation("from hour is required");
        }
        return reader.snapshotMovements(fromHour, toHour);
    }

    @Override
    public void close() {
        registries.close();
        writer.close();
        if (reader != writer) {
            reader.close();
        }
    }

    private static PGSimpleDataSource dataSource(String jdbcUrl, String user, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(jdbcUrl);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        return dataSource;
    }

    /**
     * Moves value between an account and the bank shard pool. The asset can be fiat or
     * crypto; only the direction differs. {@code bizType} says DEPOSIT or WITHDRAWAL.
     */
    private Transfer moveViaBank(CashMovement movement, String fromAccountId, String toAccountId, String bizType) {
        if (movement == null || blank(movement.requestId())) {
            throw new LedgerViolation("request_id is required");
        }
        String accountId = required(movement.accountId());
        String balanceType = required(movement.balanceType());
        String currency = required(movement.currency());
        BigDecimal amount = movement.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerViolation("Amount (" + (amount == null ? "null" : amount.toPlainString()) + ") must be positive");
        }
        String from = fromAccountId == null ? accountId : fromAccountId;
        String to = toAccountId == null ? accountId : toAccountId;
        int typeId = registries.balanceTypeId(balanceType);
        String typeCode = registries.balanceTypeCode(typeId);
        if (typeCode != null) {
            writer.ensureBankPool(typeCode, typeId, currency, bankPoolSize, true);
        }
        return post(new Posting(
                from, Integer.toString(typeId), to, Integer.toString(typeId), currency, amount,
                movement.requestId().strip(), null, bizType, true));
    }

    private static int poolSize(int poolSize) {
        if (poolSize < 1 || poolSize > MAX_BANK_POOL_SIZE) {
            throw new LedgerViolation("bank pool size (" + poolSize + ") must be between 1 and 1024");
        }
        return poolSize;
    }

    private static String required(String value) {
        if (blank(value)) {
            throw new LedgerViolation("account_id, balance_type, and currency are required");
        }
        return value.strip();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
