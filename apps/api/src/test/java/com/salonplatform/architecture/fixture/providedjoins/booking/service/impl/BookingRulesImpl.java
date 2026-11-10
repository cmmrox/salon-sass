package com.salonplatform.architecture.fixture.providedjoins.booking.service.impl;

import com.salonplatform.architecture.fixture.providedjoins.booking.BookingRules;
import org.springframework.transaction.annotation.Transactional;

/** Reads in an annotated transaction, which joins its caller's by default. */
class BookingRulesImpl implements BookingRules {

    @Override
    @Transactional(readOnly = true)
    public int holdMinutes() {
        return 10;
    }
}
