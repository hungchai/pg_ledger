package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;

import java.math.BigDecimal;
import java.util.List;

public interface LedgerStore extends AutoCloseable {
    BalanceType createBalanceType(CreateBalanceType command);

    List<BalanceType> balanceTypes();

    Account createAccount(CreateAccount command);

    Transfer post(Posting posting);

    int ensureBankPool(String balanceType, String currency, int poolSize, boolean keepExisting);

    Transfer postCash(String direction, String requestId, String accountId, String balanceType,
                      String currency, BigDecimal amount, int poolSize);

    Account deleteAccount(String accountId, String balanceType, String currency);

    BigDecimal bankPosition(String balanceType, String currency);

    List<Account> bankShards(String balanceType, String currency);

    List<Account> balances(String accountId);

    Account balance(String accountId, String balanceType, String currency);

    JournalPage journals(int page, int size);

    @Override
    void close();
}
