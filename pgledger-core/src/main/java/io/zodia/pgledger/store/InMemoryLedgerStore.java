package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.Entry;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory stand-in for {@code pgledger_create_account} and {@code pgledger_create_transfer}.
 * Balance rows are locked in sorted internal id order. Ids are monotonic; Postgres uses
 * {@code pgledger_generate_id}.
 */
public final class InMemoryLedgerStore implements LedgerStore {
    private final ConcurrentHashMap<String, BalanceTypeRow> balanceTypes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BalanceRow> byKey = new ConcurrentHashMap<>();
    private final ReentrantLock structure = new ReentrantLock();
    private final Object journal = new Object();
    private final ArrayList<Transfer> transfers = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @Override
    public BalanceType createBalanceType(CreateBalanceType command) {
        String code = command.code();
        structure.lock();
        try {
            if (balanceTypes.containsKey(code)) {
                throw new LedgerViolation("balance type already exists");
            }
            Instant now = Instant.now();
            BalanceTypeRow row = new BalanceTypeRow(code, command.name(), command.description(), now, now);
            balanceTypes.put(code, row);
            return snapshot(row);
        } finally {
            structure.unlock();
        }
    }

    @Override
    public List<BalanceType> balanceTypes() {
        structure.lock();
        try {
            ArrayList<BalanceType> rows = new ArrayList<>(balanceTypes.size());
            for (BalanceTypeRow row : balanceTypes.values()) {
                rows.add(snapshot(row));
            }
            rows.sort(Comparator.comparing(BalanceType::code));
            return List.copyOf(rows);
        } finally {
            structure.unlock();
        }
    }

    @Override
    public Account createAccount(CreateAccount command) {
        String key = key(command.accountId(), command.balanceType(), command.currency());
        structure.lock();
        try {
            if (!balanceTypes.containsKey(command.balanceType())) {
                throw new LedgerViolation("balance type not found: " + command.balanceType());
            }
            if (byKey.containsKey(key)) {
                throw new LedgerViolation("account already exists");
            }
            Instant now = Instant.now();
            BalanceRow row = new BalanceRow(
                    nextId("pgla_"),
                    command.accountId(),
                    command.balanceType(),
                    command.name(),
                    command.currency(),
                    BigDecimal.ZERO,
                    0L,
                    command.allowNegativeBalance().booleanValue(),
                    command.allowPositiveBalance().booleanValue(),
                    copyMetadata(command.metadata()),
                    now,
                    now);
            Account created = snapshot(row);
            byKey.put(key, row);
            return created;
        } finally {
            structure.unlock();
        }
    }

    @Override
    public Transfer post(Posting posting) {
        BigDecimal amount = posting.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new LedgerViolation("Amount (" + (amount == null ? "null" : amount.toPlainString()) + ") must be positive");
        }
        BalanceRow from = resolve(posting.fromAccountId(), posting.fromBalanceType(), posting.currency());
        BalanceRow to = resolve(posting.toAccountId(), posting.toBalanceType(), posting.currency());
        if (from.id.equals(to.id)) {
            throw new LedgerViolation("Cannot transfer to the same account (id=" + from.id + ")");
        }
        if (!from.currency.equals(to.currency)) {
            throw new LedgerViolation("Cannot transfer between different currencies (" + from.currency + " and " + to.currency + ")");
        }
        BalanceRow first = from.id.compareTo(to.id) <= 0 ? from : to;
        BalanceRow second = first == from ? to : from;
        first.lock.lock();
        try {
            second.lock.lock();
            try {
                BigDecimal fromNext = from.balance.subtract(amount);
                BigDecimal toNext = to.balance.add(amount);
                check(from, fromNext);
                check(to, toNext);
                Instant now = Instant.now();
                BigDecimal fromPrevious = from.balance;
                BigDecimal toPrevious = to.balance;
                from.balance = fromNext;
                from.version++;
                from.updatedAt = now;
                to.balance = toNext;
                to.version++;
                to.updatedAt = now;
                String transferId = nextId("pglt_");
                Entry debit = new Entry(nextId("pgle_"), from.accountId, from.balanceType, from.currency,
                        amount.negate(), fromPrevious, fromNext, from.version, now);
                Entry credit = new Entry(nextId("pgle_"), to.accountId, to.balanceType, to.currency,
                        amount, toPrevious, toNext, to.version, now);
                Transfer transfer = new Transfer(transferId, from.accountId, from.balanceType, to.accountId, to.balanceType,
                        from.currency, amount, now, now, copyMetadata(posting.metadata()), List.of(debit, credit));
                synchronized (journal) {
                    transfers.add(transfer);
                }
                return transfer;
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
    }

    @Override
    public List<Account> balances(String accountId) {
        ArrayList<BalanceRow> matched = new ArrayList<>();
        for (BalanceRow row : byKey.values()) {
            if (row.accountId.equals(accountId)) {
                matched.add(row);
            }
        }
        matched.sort(Comparator.comparing(row -> row.id));
        lockAll(matched);
        try {
            ArrayList<Account> views = new ArrayList<>(matched.size());
            for (int i = 0; i < matched.size(); i++) {
                views.add(snapshot(matched.get(i)));
            }
            views.sort(Comparator.comparing(Account::balanceType).thenComparing(Account::currency));
            return List.copyOf(views);
        } finally {
            unlockAll(matched);
        }
    }

    @Override
    public Account balance(String accountId, String balanceType, String currency) {
        BalanceRow row = byKey.get(key(accountId, balanceType, currency));
        if (row == null) {
            return null;
        }
        row.lock.lock();
        try {
            return snapshot(row);
        } finally {
            row.lock.unlock();
        }
    }

    @Override
    public JournalPage journals(int page, int size) {
        synchronized (journal) {
            int total = transfers.size();
            long offset = (long) page * (long) size;
            if (offset >= total) {
                return new JournalPage(page, size, total, false, List.of());
            }
            int end = total - (int) offset;
            int begin = Math.max(0, end - size);
            int count = end - begin;
            ArrayList<Transfer> pageRows = new ArrayList<>(count);
            for (int i = end - 1; i >= begin; i--) {
                pageRows.add(transfers.get(i));
            }
            boolean hasNext = offset + count < total;
            return new JournalPage(page, size, total, hasNext, List.copyOf(pageRows));
        }
    }

    @Override
    public void close() {
    }

    private BalanceRow resolve(String accountId, String balanceType, String currency) {
        BalanceRow exact = byKey.get(key(accountId, balanceType, currency));
        if (exact != null) {
            return exact;
        }
        String other = null;
        for (BalanceRow row : byKey.values()) {
            if (row.accountId.equals(accountId) && row.balanceType.equals(balanceType)) {
                if (other == null || row.currency.compareTo(other) < 0) {
                    other = row.currency;
                }
            }
        }
        if (other != null) {
            throw new LedgerViolation("Cannot transfer between different currencies (" + other + " and " + currency + ")");
        }
        throw new LedgerViolation("Account not found (account_id=" + accountId
                + ", balance_type=" + balanceType + ", currency=" + currency + ")");
    }

    private static void check(BalanceRow row, BigDecimal next) {
        if (!row.allowNegative && next.signum() < 0) {
            throw new LedgerViolation("Account (id=" + row.id + ", name=" + row.name + ") does not allow negative balance");
        }
        if (!row.allowPositive && next.signum() > 0) {
            throw new LedgerViolation("Account (id=" + row.id + ", name=" + row.name + ") does not allow positive balance");
        }
    }

    private static void lockAll(List<BalanceRow> rows) {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).lock.lock();
        }
    }

    private static void unlockAll(List<BalanceRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            rows.get(i).lock.unlock();
        }
    }

    private String nextId(String prefix) {
        long n = sequence.incrementAndGet();
        char[] digits = new char[16];
        for (int i = 15; i >= 0; i--) {
            digits[i] = (char) ('0' + (int) (n % 10L));
            n /= 10L;
        }
        return prefix + new String(digits);
    }

    private static String key(String accountId, String balanceType, String currency) {
        return accountId + '\0' + balanceType + '\0' + currency;
    }

    private static Account snapshot(BalanceRow row) {
        return new Account(row.id, row.accountId, row.balanceType, row.name, row.currency, row.balance, row.version,
                row.allowNegative, row.allowPositive, row.metadata, row.createdAt, row.updatedAt);
    }

    private static BalanceType snapshot(BalanceTypeRow row) {
        return new BalanceType(row.code, row.name, row.description, row.createdAt, row.updatedAt);
    }

    private static Map<String, Object> copyMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        HashMap<String, Object> copy = new HashMap<>(metadata.size());
        copy.putAll(metadata);
        return Collections.unmodifiableMap(copy);
    }

    private static final class BalanceTypeRow {
        private final String code;
        private final String name;
        private final String description;
        private final Instant createdAt;
        private final Instant updatedAt;

        private BalanceTypeRow(String code, String name, String description, Instant createdAt, Instant updatedAt) {
            this.code = code;
            this.name = name;
            this.description = description;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    private static final class BalanceRow {
        private final String id;
        private final String accountId;
        private final String balanceType;
        private final String name;
        private final String currency;
        private BigDecimal balance;
        private long version;
        private final boolean allowNegative;
        private final boolean allowPositive;
        private final Map<String, Object> metadata;
        private final Instant createdAt;
        private Instant updatedAt;
        private final ReentrantLock lock = new ReentrantLock();

        private BalanceRow(String id, String accountId, String balanceType, String name, String currency,
                           BigDecimal balance, long version, boolean allowNegative, boolean allowPositive,
                           Map<String, Object> metadata, Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.accountId = accountId;
            this.balanceType = balanceType;
            this.name = name;
            this.currency = currency;
            this.balance = balance;
            this.version = version;
            this.allowNegative = allowNegative;
            this.allowPositive = allowPositive;
            this.metadata = metadata;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }
}
