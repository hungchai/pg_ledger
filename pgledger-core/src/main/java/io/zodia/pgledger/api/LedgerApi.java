package io.zodia.pgledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.zodia.pgledger.api.NumberAsTextDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public final class LedgerApi {
    private LedgerApi() {
    }

    public record CreateBalanceType(
            String code,
            String name,
            String description,
            Boolean allowNegative,
            Boolean allowPositive) {

        public CreateBalanceType(String code, String name, String description) {
            this(code, name, description, null, null);
        }
    }

    public record BalanceType(
            int id,
            String code,
            String name,
            String description,
            boolean allowNegative,
            boolean allowPositive,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CreateAccount(
            String accountId,
            String balanceType,
            String currency,
            String name,
            Map<String, Object> metadata,
            String accountClass) {
    }

    public record DeleteAccount(String accountId, String balanceType, String currency) {
    }

    public record CashMovement(
            String requestId,
            String accountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String balanceType,
            String currency,
            BigDecimal amount) {
    }

    /**
     * Posting legs. {@code autoCreate} creates missing (account, balanceType,
     * currency) rows on the fly; default true for deposit/withdrawal, false for
     * plain postings where a typo should fail loudly.
     */
    public record Posting(
            String fromAccountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String fromBalanceType,
            String toAccountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String toBalanceType,
            String currency,
            BigDecimal amount,
            String requestId,
            String bizReference,
            String bizType,
            Boolean autoCreate) {

        public Posting(
                String fromAccountId,
                String fromBalanceType,
                String toAccountId,
                String toBalanceType,
                String currency,
                BigDecimal amount,
                String requestId,
                String bizReference) {
            this(fromAccountId, fromBalanceType, toAccountId, toBalanceType,
                    currency, amount, requestId, bizReference, null, null);
        }

        public Posting(
                String fromAccountId,
                String fromBalanceType,
                String toAccountId,
                String toBalanceType,
                String currency,
                BigDecimal amount,
                String requestId,
                String bizReference,
                String bizType) {
            this(fromAccountId, fromBalanceType, toAccountId, toBalanceType,
                    currency, amount, requestId, bizReference, bizType, null);
        }
    }

    /** One leg of a multi-leg posting (e.g. RFQ). Balance types may be codes or ids. */
    public record PostingLeg(
            String fromAccountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String fromBalanceType,
            String toAccountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String toBalanceType,
            String currency,
            BigDecimal amount) {
    }

    /**
     * Atomic multi-leg posting. One {@code requestId}, one SQL {@code pgledger_create_transfers}.
     * Single-leg callers keep using {@link Posting}. {@code autoCreate} null means true.
     */
    public record PostingBatch(
            String requestId,
            String bizReference,
            String bizType,
            List<PostingLeg> legs,
            Boolean autoCreate) {

        public PostingBatch(String requestId, String bizReference, String bizType, List<PostingLeg> legs) {
            this(requestId, bizReference, bizType, legs, null);
        }
    }

    public record Account(
            String id,
            String accountId,
            String balanceType,
            String name,
            String currency,
            BigDecimal balance,
            long version,
            boolean allowNegativeBalance,
            boolean allowPositiveBalance,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt,
            String accountClass,
            boolean deleted) {
    }

    public record Entry(
            String id,
            String accountId,
            int balanceType,
            String currency,
            BigDecimal amount,
            BigDecimal previousBalance,
            BigDecimal currentBalance,
            long version,
            Instant createdAt) {
    }

    public record Transfer(
            String id,
            long seq,
            String fromAccountId,
            int fromBalanceType,
            String toAccountId,
            int toBalanceType,
            String currency,
            BigDecimal amount,
            Instant createdAt,
            Instant eventAt,
            String requestId,
            String bizType,
            String bizReference,
            List<Entry> entries) {
    }

    public record JournalPage(int page, int size, long total, boolean hasNext, List<Transfer> transfers) {
    }

    /**
     * Balance query: one or more account ids, optional balance-type / currency
     * filters. Codes or numeric ids are both accepted. A missing filter means
     * "all balance types" / "all currencies".
     */
    public record BalanceQuery(
            List<String> accountIds,
            String balanceType,
            String currency) {
    }

    /** One hourly snapshot row for a single account balance. */
    public record BalanceSnapshot(
            Instant snapshotHour,
            String accountId,
            String balanceType,
            String currency,
            String accountClass,
            int year,
            int month,
            int day,
            int hour,
            BigDecimal balance,
            BigDecimal previousBalance,
            long version,
            boolean deleted) {
    }

    /**
     * Net movement between two snapshot hours: opening = closing balance at (or
     * before) fromHour, closing = balance at toHour, movement = closing - opening.
     */
    public record SnapshotMovement(
            String balanceType,
            String currency,
            String accountClass,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal movement) {
    }

    /** Per-account movement for client statements. No grouping. */
    public record AccountMovement(
            String accountId,
            String name,
            String balanceType,
            String currency,
            BigDecimal openingBalance,
            BigDecimal closingBalance,
            BigDecimal movement) {
    }
}
