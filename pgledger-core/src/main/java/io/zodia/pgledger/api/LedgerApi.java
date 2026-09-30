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

    public record Posting(
            String fromAccountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String fromBalanceType,
            String toAccountId,
            @JsonDeserialize(using = NumberAsTextDeserializer.class) String toBalanceType,
            String currency,
            BigDecimal amount,
            String requestId,
            String bizReference,
            String bizType) {
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
                    currency, amount, requestId, bizReference, null);
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
     * Single-leg callers keep using {@link Posting}.
     */
    public record PostingBatch(
            String requestId,
            String bizReference,
            String bizType,
            List<PostingLeg> legs) {
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
}
