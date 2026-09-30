package io.zodia.pgledger.store.read;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-side queries. Plain SELECTs only; the caller's DataSource decides where
 * they run (replica under ShardingSphere read/write splitting). Balance type,
 * currency, and account class ids become codes through the registry snapshot in
 * {@link LedgerReadService}, so the JSON contract stays codes.
 *
 * <p>Plain MyBatis only: pgledger-core has no Spring dependency. The
 * SqlSessionFactory is built by whoever owns the DataSource (PgLedger for the
 * store, Spring configuration for the REST layer).
 */
public interface LedgerReadMapper {

    @Select("""
            SELECT id, account_id, balance_type_id, name, currency_id, balance, version,
                   allow_negative_balance, allow_positive_balance, metadata::text AS metadata,
                   created_at, updated_at, account_class_id, deleted
            FROM pgledger_accounts
            WHERE account_id = #{accountId}
            """)
    List<AccountRow> balancesByAccount(@Param("accountId") String accountId);

    @Select("""
            SELECT id, account_id, balance_type_id, name, currency_id, balance, version,
                   allow_negative_balance, allow_positive_balance, metadata::text AS metadata,
                   created_at, updated_at, account_class_id, deleted
            FROM pgledger_accounts
            WHERE account_id = #{accountId}
              AND balance_type_id = #{balanceTypeId}
              AND currency_id = #{currencyId}
            """)
    AccountRow balance(@Param("accountId") String accountId,
                       @Param("balanceTypeId") int balanceTypeId,
                       @Param("currencyId") int currencyId);

    @Select("""
            SELECT COALESCE(SUM(balance), 0)
            FROM pgledger_accounts
            WHERE account_class_id = #{classId}
              AND balance_type_id = #{balanceTypeId}
              AND currency_id = #{currencyId}
            """)
    BigDecimal bankPosition(@Param("classId") int classId,
                            @Param("balanceTypeId") int balanceTypeId,
                            @Param("currencyId") int currencyId);

    /**
     * Raw row. Ids are resolved to codes in the service layer. Constructed by
     * MyBatis with positional constructor binding, column order matters.
     */
    record AccountRow(
            String id,
            String accountId,
            int balanceTypeId,
            String name,
            int currencyId,
            BigDecimal balance,
            long version,
            boolean allowNegativeBalance,
            boolean allowPositiveBalance,
            String metadata,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            int accountClassId,
            boolean deleted) {
    }
}
