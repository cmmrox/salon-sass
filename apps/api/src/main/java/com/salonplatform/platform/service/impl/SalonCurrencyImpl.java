package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.platform.repository.SalonCurrencyStore;
import com.salonplatform.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One read in a read-only tenant transaction, which sets the request's salon for the function to answer. */
@Service
class SalonCurrencyImpl implements SalonCurrency {

    private final SalonCurrencyStore store;
    private final TransactionTemplate reading;

    SalonCurrencyImpl(SalonCurrencyStore store, PlatformTransactionManager transactions) {
        this.store = store;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public String current() {
        TenantContext.require();
        return reading.execute(status -> store.forCurrentSalon().orElse(DEFAULT));
    }
}
