package io.zodia.pgledger.store.read;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

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
 *
 * <p>Rows bind with explicit {@code @ConstructorArgs}: positional inference
 * plus mapUnderscoreToCamelCase mis-assigns text columns to int components
 * when a record mixes types. Column order in the SELECT still matters — it
 * must follow the @Arg order.
 */
public interface LedgerReadMapper {

    String ACCOUNT_COLUMNS = """
            a.id,
            a.account_id,
            a.balance_type_id,
            a.name,
            a.currency_id,
            a.balance,
            a.version,
            bt.allow_negative OR ac.code = 'BANK' AS allow_negative_balance,
            bt.allow_positive OR ac.code = 'BANK' AS allow_positive_balance,
            a.metadata::text AS metadata,
            a.created_at,
            a.updated_at,
            a.account_class_id,
            a.deleted
            """;

    String ACCOUNT_JOINS = """
            FROM pgledger_accounts a
            JOIN pgledger_balance_types bt ON bt.id = a.balance_type_id
            JOIN pgledger_account_classes ac ON ac.id = a.account_class_id
            JOIN pgledger_currencies c ON c.id = a.currency_id
            """;

    @Select("SELECT " + ACCOUNT_COLUMNS + ACCOUNT_JOINS + " WHERE a.account_id = #{accountId}")
    @ConstructorArgs({
            @Arg(column = "id", javaType = String.class),
            @Arg(column = "account_id", javaType = String.class),
            @Arg(column = "balance_type_id", javaType = int.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "currency_id", javaType = int.class),
            @Arg(column = "balance", javaType = BigDecimal.class),
            @Arg(column = "version", javaType = long.class),
            @Arg(column = "allow_negative_balance", javaType = boolean.class),
            @Arg(column = "allow_positive_balance", javaType = boolean.class),
            @Arg(column = "metadata", javaType = String.class, jdbcType = JdbcType.VARCHAR),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "account_class_id", javaType = int.class),
            @Arg(column = "deleted", javaType = boolean.class),
    })
    List<AccountRow> balancesByAccount(@Param("accountId") String accountId);

    @Select("SELECT " + ACCOUNT_COLUMNS + ACCOUNT_JOINS + """
            WHERE a.account_id = #{accountId}
              AND a.balance_type_id = #{balanceTypeId}
              AND a.currency_id = #{currencyId}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = String.class),
            @Arg(column = "account_id", javaType = String.class),
            @Arg(column = "balance_type_id", javaType = int.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "currency_id", javaType = int.class),
            @Arg(column = "balance", javaType = BigDecimal.class),
            @Arg(column = "version", javaType = long.class),
            @Arg(column = "allow_negative_balance", javaType = boolean.class),
            @Arg(column = "allow_positive_balance", javaType = boolean.class),
            @Arg(column = "metadata", javaType = String.class, jdbcType = JdbcType.VARCHAR),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "account_class_id", javaType = int.class),
            @Arg(column = "deleted", javaType = boolean.class),
    })
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

    @Select("SELECT " + ACCOUNT_COLUMNS + ACCOUNT_JOINS + """
            WHERE a.account_id = ANY (#{accountIds}::text[])
              AND (#{balanceTypeId}::int IS NULL OR a.balance_type_id = #{balanceTypeId})
              AND (#{currencyId}::int IS NULL OR a.currency_id = #{currencyId})
            ORDER BY a.account_id, bt.code, c.code
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = String.class),
            @Arg(column = "account_id", javaType = String.class),
            @Arg(column = "balance_type_id", javaType = int.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "currency_id", javaType = int.class),
            @Arg(column = "balance", javaType = BigDecimal.class),
            @Arg(column = "version", javaType = long.class),
            @Arg(column = "allow_negative_balance", javaType = boolean.class),
            @Arg(column = "allow_positive_balance", javaType = boolean.class),
            @Arg(column = "metadata", javaType = String.class, jdbcType = JdbcType.VARCHAR),
            @Arg(column = "created_at", javaType = java.time.Instant.class),
            @Arg(column = "updated_at", javaType = java.time.Instant.class),
            @Arg(column = "account_class_id", javaType = int.class),
            @Arg(column = "deleted", javaType = boolean.class),
    })
    List<AccountRow> balancesQuery(@Param("accountIds") String[] accountIds,
                                   @Param("balanceTypeId") Integer balanceTypeId,
                                   @Param("currencyId") Integer currencyId);

    @Select("SELECT count(*) FROM pgledger_transfers")
    long journalCount();

    /**
     * One journal page. The inner query picks the page newest-first; the outer
     * join fetches their entries. Ordering happens entirely inside the inner
     * query, so an offset past the end returns no rows and pages never repeat.
     * total_count comes from a window function over the same snapshot as the
     * rows, so hasNext cannot disagree with the page just served.
     */
    @Select("""
            SELECT
                page.total_count,
                t.id AS transfer_id,
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
            FROM (
                SELECT id, created_at,
                       count(*) OVER () AS total_count
                FROM pgledger_transfers
                ORDER BY created_at DESC, id DESC
                LIMIT #{size} OFFSET #{offset}
            ) page
            JOIN pgledger_transfers t ON t.id = page.id
            JOIN pgledger_accounts fa ON fa.id = t.from_account_id
            JOIN pgledger_accounts ta ON ta.id = t.to_account_id
            LEFT JOIN pgledger_entries e ON e.transfer_id = t.id
            LEFT JOIN pgledger_accounts ea ON ea.id = e.account_id
            ORDER BY page.created_at DESC, page.id DESC, e.id
            """)
    @ConstructorArgs({
            @Arg(column = "total_count", javaType = long.class),
            @Arg(column = "transfer_id", javaType = String.class),
            @Arg(column = "from_account_id", javaType = String.class),
            @Arg(column = "from_balance_type_id", javaType = int.class),
            @Arg(column = "to_account_id", javaType = String.class),
            @Arg(column = "to_balance_type_id", javaType = int.class),
            @Arg(column = "currency_id", javaType = int.class),
            @Arg(column = "transfer_amount", javaType = BigDecimal.class),
            @Arg(column = "transfer_created_at", javaType = java.time.Instant.class),
            @Arg(column = "event_at", javaType = java.time.Instant.class),
            @Arg(column = "request_id", javaType = String.class),
            @Arg(column = "biz_type_id", javaType = int.class),
            @Arg(column = "biz_reference", javaType = String.class),
            @Arg(column = "entry_id", javaType = String.class),
            @Arg(column = "entry_account_id", javaType = String.class),
            @Arg(column = "entry_balance_type_id", javaType = Integer.class),
            @Arg(column = "entry_currency_id", javaType = Integer.class),
            @Arg(column = "entry_amount", javaType = BigDecimal.class),
            @Arg(column = "account_previous_balance", javaType = BigDecimal.class),
            @Arg(column = "account_current_balance", javaType = BigDecimal.class),
            @Arg(column = "account_version", javaType = Long.class),
            @Arg(column = "entry_created_at", javaType = java.time.Instant.class),
    })
    List<JournalRow> journals(@Param("size") int size, @Param("offset") long offset);

    /**
     * One joined row per transfer leg. Transfers repeat once per entry; the
     * service groups them. Entry columns are null on the left join when a
     * transfer has no entries yet.
     */
    record JournalRow(
            long totalCount,
            String transferId,
            String fromAccountId,
            int fromBalanceTypeId,
            String toAccountId,
            int toBalanceTypeId,
            int currencyId,
            BigDecimal transferAmount,
            java.time.Instant transferCreatedAt,
            java.time.Instant eventAt,
            String requestId,
            int bizTypeId,
            String bizReference,
            String entryId,
            String entryAccountId,
            Integer entryBalanceTypeId,
            Integer entryCurrencyId,
            BigDecimal entryAmount,
            BigDecimal accountPreviousBalance,
            BigDecimal accountCurrentBalance,
            Long accountVersion,
            java.time.Instant entryCreatedAt) {
    }

    /**
     * Raw row. Ids are resolved to codes in the service layer.
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
