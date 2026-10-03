package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceSnapshot;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.SnapshotMovement;
import io.zodia.pgledger.api.LedgerApi.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface LedgerStore extends AutoCloseable {
    BalanceType createBalanceType(CreateBalanceType command);

    List<BalanceType> balanceTypes();

    Account createAccount(CreateAccount command);

    Transfer post(Posting posting);

    /** Atomic multi-leg post. Every leg shares the same requestId. */
    List<Transfer> post(List<Posting> legs);

    int ensureBankPool(String balanceTypeCode, int balanceTypeId, String currency, int poolSize, boolean keepExisting);

    Account deleteAccount(String accountId, String balanceType, String currency);

    BigDecimal bankPosition(String balanceType, String currency);

    List<Account> bankShards(String balanceType, String currency);

    List<Account> balances(String accountId);

    Account balance(String accountId, String balanceType, String currency);

    JournalPage journals(int page, int size);

    /** Cuts the hourly snapshot at the given UTC hour; returns rows written. */
    long cutBalanceSnapshot(Instant hour);

    /** Snapshot rows for one hour (all dims), ordered by account id. */
    List<BalanceSnapshot> snapshots(Instant hour);

    /** Movement per (type, currency, class) between fromHour (exclusive) and toHour inclusive; null to = latest. */
    List<SnapshotMovement> snapshotMovements(Instant fromHour, Instant toHour);

    @Override
    void close();
}
