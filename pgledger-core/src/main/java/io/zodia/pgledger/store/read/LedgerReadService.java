package io.zodia.pgledger.store.read;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.Entry;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Transfer;
import io.zodia.pgledger.store.LedgerJson;
import io.zodia.pgledger.store.RegistryCache;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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

    /**
     * Multi-account balance query. Optional filters: null or blank balanceType /
     * currency means all. Unknown codes yield an empty result, not an error.
     */
    public List<Account> balancesQuery(io.zodia.pgledger.api.LedgerApi.BalanceQuery query) {
        List<String> ids = query.accountIds() == null ? List.of() : query.accountIds();
        ArrayList<String> cleaned = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                cleaned.add(id.strip());
            }
        }
        if (cleaned.isEmpty()) {
            throw new io.zodia.pgledger.store.LedgerViolation("accountIds is required");
        }
        Integer typeId = query.balanceType() == null || query.balanceType().isBlank()
                ? null
                : registries.findBalanceTypeId(query.balanceType());
        Integer currencyId = query.currency() == null || query.currency().isBlank()
                ? null
                : registries.currencyId(query.currency());
        if ((query.balanceType() != null && !query.balanceType().isBlank() && typeId == null)
                || (query.currency() != null && !query.currency().isBlank() && currencyId == null)) {
            return List.of();
        }
        List<LedgerReadMapper.AccountRow> rows =
                mapper.balancesQuery(cleaned.toArray(new String[0]), typeId, currencyId);
        ArrayList<Account> result = new ArrayList<>(rows.size());
        for (LedgerReadMapper.AccountRow row : rows) {
            result.add(toAccount(row));
        }
        return List.copyOf(result);
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

    /**
     * Groups joined rows into transfers, page order preserved: newest transfer
     * first, entries by id inside a transfer. Rows past the end yield an empty
     * page, never a repeat of an earlier one.
     */
    public JournalPage journals(int page, int size) {
        long offset = (long) page * (long) size;
        List<LedgerReadMapper.JournalRow> rows = mapper.journals(size, offset);
        // Window total rides the same snapshot as the rows. An empty page (offset
        // past the end) has no rows to carry it; the standalone count then cannot
        // contradict hasNext because the page is empty either way.
        long total = rows.isEmpty() ? mapper.journalCount() : rows.get(0).totalCount();
        LinkedHashMap<String, Transfer> transfers = new LinkedHashMap<>(rows.size());
        LinkedHashMap<String, ArrayList<Entry>> entries = new LinkedHashMap<>(rows.size());
        for (LedgerReadMapper.JournalRow row : rows) {
            transfers.computeIfAbsent(row.transferId(), ignored -> toTransfer(row));
            if (row.entryId() != null) {
                entries.computeIfAbsent(row.transferId(), ignored -> new ArrayList<>(2))
                        .add(toEntry(row));
            }
        }
        ArrayList<Transfer> pageRows = new ArrayList<>(transfers.size());
        for (Transfer transfer : transfers.values()) {
            List<Entry> lines = entries.get(transfer.id());
            pageRows.add(withEntries(transfer, lines == null ? List.of() : List.copyOf(lines)));
        }
        // pageRows counts transfers, not joined rows; hasNext compares like with like.
        boolean hasNext = offset + pageRows.size() < total;
        return new JournalPage(page, size, total, hasNext, List.copyOf(pageRows));
    }

    private Transfer toTransfer(LedgerReadMapper.JournalRow row) {
        int currencyId = row.currencyId();
        int bizTypeId = row.bizTypeId();
        return new Transfer(
                row.transferId(),
                row.transferSeq() == null ? 0L : row.transferSeq().longValue(),
                row.fromAccountId(),
                row.fromBalanceTypeId(),
                row.toAccountId(),
                row.toBalanceTypeId(),
                code(registries.currencyCode(currencyId), currencyId),
                row.transferAmount(),
                row.transferCreatedAt(),
                row.eventAt(),
                row.requestId(),
                code(registries.bizTypeCode(bizTypeId), bizTypeId),
                row.bizReference(),
                List.of());
    }

    private Entry toEntry(LedgerReadMapper.JournalRow row) {
        int currencyId = row.entryCurrencyId() == null ? 0 : row.entryCurrencyId().intValue();
        int balanceTypeId = row.entryBalanceTypeId() == null ? 0 : row.entryBalanceTypeId().intValue();
        return new Entry(
                row.entryId(),
                row.entryAccountId(),
                balanceTypeId,
                code(registries.currencyCode(currencyId), currencyId),
                row.entryAmount(),
                row.accountPreviousBalance(),
                row.accountCurrentBalance(),
                row.accountVersion() == null ? 0L : row.accountVersion().longValue(),
                row.entryCreatedAt());
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
