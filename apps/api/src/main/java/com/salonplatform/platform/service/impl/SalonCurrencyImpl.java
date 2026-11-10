package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.SalonCurrency;
import com.salonplatform.platform.repository.SalonCurrencyStore;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.tenancy.TenantContext;
import org.springframework.stereotype.Service;

/** One read in a read-only tenant transaction, which sets the request's salon for the function to answer. */
@Service
class SalonCurrencyImpl implements SalonCurrency {

    private final SalonCurrencyStore store;
    private final ReadOnlyWork reading;

    SalonCurrencyImpl(SalonCurrencyStore store, ReadOnlyWork reading) {
        this.store = store;
        this.reading = reading;
    }

    @Override
    public String current() {
        TenantContext.require();
        return reading.read(() -> store.forCurrentSalon().orElse(DEFAULT));
    }
}
