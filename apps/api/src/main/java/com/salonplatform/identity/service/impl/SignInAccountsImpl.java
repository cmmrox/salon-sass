package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.SignInAccount;
import com.salonplatform.identity.SignInAccounts;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.List;
import org.springframework.stereotype.Service;

/** Reads the accounts in one query under the salon's tenant filter and row-level security. */
@Service
class SignInAccountsImpl implements SignInAccounts {

    private final StaffAccountRepository accounts;
    private final ReadOnlyWork reading;

    SignInAccountsImpl(StaffAccountRepository accounts, ReadOnlyWork reading) {
        this.accounts = accounts;
        this.reading = reading;
    }

    @Override
    public List<SignInAccount> active() {
        TenantContext.require();
        return reading.read(() -> accounts.findActiveOldestFirst().stream()
                .map(account -> new SignInAccount(
                        account.getId(),
                        account.getEmail() != null ? account.getEmail() : account.getMobile(),
                        account.getRole().name()))
                .toList());
    }
}
