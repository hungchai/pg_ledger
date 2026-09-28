package io.zodia.pgledger.store;

import io.zodia.pgledger.api.LedgerApi.Account;
import io.zodia.pgledger.api.LedgerApi.BalanceType;
import io.zodia.pgledger.api.LedgerApi.CreateAccount;
import io.zodia.pgledger.api.LedgerApi.CreateBalanceType;
import io.zodia.pgledger.api.LedgerApi.JournalPage;
import io.zodia.pgledger.api.LedgerApi.Posting;
import io.zodia.pgledger.api.LedgerApi.Transfer;

import java.util.List;

public interface LedgerStore extends AutoCloseable {
    BalanceType createBalanceType(CreateBalanceType command);

    List<BalanceType> balanceTypes();

    Account createAccount(CreateAccount command);

    Transfer post(Posting posting);

    List<Account> balances(String accountId);

    Account balance(String accountId, String balanceType, String currency);

    JournalPage journals(int page, int size);

    @Override
    void close();
}
