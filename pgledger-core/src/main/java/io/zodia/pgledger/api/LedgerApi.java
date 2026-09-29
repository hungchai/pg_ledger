package io.zodia.pgledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class LedgerApi {
    private LedgerApi() {
    }

    public record CreateBalanceType(
            String code,
            String name,
            String description) {
    }

    public record BalanceType(
            String code,
            String name,
            String description,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CreateAccount(
            String accountId,
            String balanceType,
            String currency,
            String name,
            Boolean allowNegativeBalance,
            Boolean allowPositiveBalance,
            Map<String, Object> metadata,
            String accountClass) {
    }

    public record DeleteAccount(String accountId, String balanceType, String currency) {
    }

    public record CashMovement(
            String requestId,
            String accountId,
            String balanceType,
            String currency,
            BigDecimal amount) {
    }

    public record Posting(
            String fromAccountId,
            String fromBalanceType,
            String toAccountId,
            String toBalanceType,
            String currency,
            BigDecimal amount,
            Map<String, Object> metadata) {
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
            String balanceType,
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
            String fromBalanceType,
            String toAccountId,
            String toBalanceType,
            String currency,
            BigDecimal amount,
            Instant createdAt,
            Instant eventAt,
            Map<String, Object> metadata,
            String requestId,
            String bizType,
            List<Entry> entries) {
    }

    public record JournalPage(int page, int size, long total, boolean hasNext, List<Transfer> transfers) {
    }
}
