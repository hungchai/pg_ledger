package io.zodia.pgledger.store.read;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.store.LedgerJson;
import io.zodia.pgledger.store.RegistryCache;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Maps MyBatis rows to API records. Id-to-code resolution reads the registry
 * snapshot in memory; no database round trip per row. Stateless over the
 * mapper and the shared read-only registry snapshot.
 */
public class LedgerReadService {

    private final LedgerReadMapper mapper;
    private final RegistryCache registries;

    public LedgerReadService(LedgerReadMapper mapper, RegistryCache registries) {
        this.mapper = mapper;
        this.registries = registries;
    }

    public List<Account> balances(String accountId) {
        List<LedgerReadMapper.AccountRow> rows = mapper.balancesByAccount(accountId);
        ArrayList<Account> sorted = new ArrayList<>(rows.size());
        for (LedgerReadMapper.AccountRow row : rows) {
            sorted.add(toAccount(row));
        }
        sorted.sort(Comparator.comparing(Account::balanceType).thenComparing(Account::currency));
        return List.copyOf(sorted);
    }

    public Account balance(String accountId, String balanceType, String currency) {
        Integer typeId = registries.findBalanceTypeId(balanceType);
        Integer currencyId = registries.currencyId(currency);
        if (typeId == null || currencyId == null) {
            return null;
        }
        LedgerReadMapper.AccountRow row = mapper.balance(accountId, typeId.intValue(), currencyId.intValue());
        return row == null ? null : toAccount(row);
    }

    public BigDecimal bankPosition(String balanceType, String currency) {
        Integer classId = registries.accountClassId("BANK");
        Integer typeId = registries.findBalanceTypeId(balanceType);
        Integer currencyId = registries.currencyId(currency);
        if (classId == null || typeId == null || currencyId == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal position = mapper.bankPosition(classId.intValue(), typeId.intValue(), currencyId.intValue());
        return position == null ? BigDecimal.ZERO : position;
    }

    private Account toAccount(LedgerReadMapper.AccountRow row) {
        int typeId = row.balanceTypeId();
        int currencyId = row.currencyId();
        int classId = row.accountClassId();
        return new Account(
                row.id(),
                row.accountId(),
                code(registries.balanceTypeCode(typeId), typeId),
                row.name(),
                code(registries.currencyCode(currencyId), currencyId),
                row.balance(),
                row.version(),
                row.allowNegativeBalance(),
                row.allowPositiveBalance(),
                metadata(row.metadata()),
                row.createdAt(),
                row.updatedAt(),
                code(registries.accountClassCode(classId), classId),
                row.deleted());
    }

    private static String code(String resolved, int id) {
        return resolved == null ? Integer.toString(id) : resolved;
    }

    private static Map<String, Object> metadata(String json) {
        return LedgerJson.map(json);
    }
}
