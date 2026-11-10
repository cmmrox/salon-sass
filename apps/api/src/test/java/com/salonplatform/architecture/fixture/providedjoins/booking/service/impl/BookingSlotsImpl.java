package com.salonplatform.architecture.fixture.providedjoins.booking.service.impl;

import com.salonplatform.architecture.fixture.providedjoins.booking.BookingSlots;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads in a transaction template of its own, which joins its caller's transaction by default. */
class BookingSlotsImpl implements BookingSlots {

    private final TransactionTemplate reading;

    BookingSlotsImpl(PlatformTransactionManager transactions) {
        this.reading = new TransactionTemplate(transactions);
    }

    @Override
    public List<String> free() {
        return reading.execute(status -> List.of());
    }
}
