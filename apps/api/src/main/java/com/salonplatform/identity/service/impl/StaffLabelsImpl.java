package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.StaffLabels;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the accounts in one query under the salon's tenant filter and row-level security. */
@Service
class StaffLabelsImpl implements StaffLabels {

    private final StaffAccountRepository accounts;
    private final TransactionTemplate reading;

    StaffLabelsImpl(StaffAccountRepository accounts, PlatformTransactionManager transactions) {
        this.accounts = accounts;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public Map<UUID, String> labels(Collection<UUID> staffIds) {
        TenantContext.require();
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        return reading.execute(status -> {
            Map<UUID, String> labels = new HashMap<>();
            for (StaffAccount account : accounts.findAllById(new HashSet<>(staffIds))) {
                String label = account.getEmail() != null ? account.getEmail() : account.getMobile();
                if (label != null) {
                    labels.put(account.getId(), label);
                }
            }
            return Map.copyOf(labels);
        });
    }
}
