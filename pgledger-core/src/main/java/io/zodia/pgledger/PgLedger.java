package io.zodia.pgledger;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CashMovement;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerStore;
import io.zodia.pgledger.store.LedgerViolation;
import io.zodia.pgledger.store.PostgresLedgerStore;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static final Set<String> ACCOUNT_CLASSES = Set.of(
            "CLIENT", "COMPANY", "BANK", "NOSTRO", "SUSPENSE", "CONTROL");

    private final LedgerStore writer;
    private final LedgerStore reader;
    private final int bankPoolSize;

    private PgLedger(LedgerStore writer, LedgerStore reader, int bankPoolSize) {
        if (bankPoolSize < 1 || bankPoolSize > MAX_BANK_POOL_SIZE) {
            throw new IllegalArgumentException("bank pool size");
        }
        this.writer = writer;
        this.reader = reader;
        this.bankPoolSize = bankPoolSize;
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
        PostgresLedgerStore writerStore = new PostgresLedgerStore(writer, true);
        PostgresLedgerStore readerStore = new PostgresLedgerStore(reader, false);
        return new PgLedger(writerStore, readerStore, bankPoolSize);
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
        PostgresLedgerStore store = new PostgresLedgerStore(dataSource, false);
        return new PgLedger(store, store, bankPoolSize);
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
        return writer.createBalanceType(new CreateBalanceType(code, name, description));
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
        String name = command.name() == null || command.name().isBlank() ? accountId : command.name().strip();
        return writer.createAccount(new CreateAccount(
                accountId,
                balanceType,
                currency,
                name,
                flag(command.allowNegativeBalance()),
                flag(command.allowPositiveBalance()),
                command.metadata(),
                accountClass(command.accountClass())));
    }

    public int ensureBankPool(String balanceType, String currency, int poolSize) {
        return writer.ensureBankPool(required(balanceType), required(currency), poolSize(poolSize), false);
    }

    public Transfer deposit(CashMovement movement) {
        return cash(movement, "BANK", null, "DEPOSIT");
    }

    public Transfer withdraw(CashMovement movement) {
        return cash(movement, null, "BANK", "WITHDRAWAL");
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
        if (posting == null
                || blank(posting.fromAccountId())
                || blank(posting.fromBalanceType())
                || blank(posting.toAccountId())
                || blank(posting.toBalanceType())
                || blank(posting.currency())) {
            throw new LedgerViolation("account_id, balance_type, and currency are required");
        }
        if (blank(posting.requestId())) {
            throw new LedgerViolation("request_id is required");
        }
        BigDecimal amount = posting.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerViolation("Amount (" + (amount == null ? "null" : amount.toPlainString()) + ") must be positive");
        }
        String bizReference = posting.bizReference();
        String bizType = posting.bizType();
        return writer.post(new Posting(
                posting.fromAccountId().strip(),
                posting.fromBalanceType().strip(),
                posting.toAccountId().strip(),
                posting.toBalanceType().strip(),
                posting.currency().strip(),
                amount,
                posting.requestId().strip(),
                bizReference == null || bizReference.isBlank() ? null : bizReference.strip(),
                bizType == null || bizType.isBlank() ? null : bizType.strip()));
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

    @Override
    public void close() {
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

    private Transfer cash(CashMovement movement, String fromAccountId, String toAccountId, String bizType) {
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
        writer.ensureBankPool(balanceType, currency, bankPoolSize, true);
        return post(new Posting(
                from, balanceType, to, balanceType, currency, amount,
                movement.requestId().strip(), null, bizType));
    }

    private static int poolSize(int poolSize) {
        if (poolSize < 1 || poolSize > MAX_BANK_POOL_SIZE) {
            throw new LedgerViolation("bank pool size (" + poolSize + ") must be between 1 and 1024");
        }
        return poolSize;
    }

    private static String accountClass(String value) {
        if (value == null || value.isBlank()) {
            return "CLIENT";
        }
        String code = value.strip().toUpperCase(Locale.ROOT);
        if (!ACCOUNT_CLASSES.contains(code)) {
            throw new LedgerViolation("account_class must be CLIENT, COMPANY, BANK, NOSTRO, SUSPENSE, or CONTROL");
        }
        return code;
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

    private static boolean flag(Boolean value) {
        return value == null || value.booleanValue();
    }
}
