package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.SignInAccount;
import com.salonplatform.identity.SignInAccounts;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the accounts in one query under the salon's tenant filter and row-level security. */
@Service
class SignInAccountsImpl implements SignInAccounts {

    private final StaffAccountRepository accounts;
    private final TransactionTemplate reading;

    SignInAccountsImpl(StaffAccountRepository accounts, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public List<SignInAccount> active() {
        TenantContext.require();
        return reading.execute(status -> accounts.findActiveOldestFirst().stream()
                .map(account -> new SignInAccount(
                        account.getId(),
                        account.getEmail() != null ? account.getEmail() : account.getMobile(),
                        account.getRole().name()))
                .toList());
    }
}
