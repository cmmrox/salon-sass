package com.salonplatform.architecture.fixture.clean.booking.service.impl;

/** A helper of the implementation beside it: package-private, no interface, not named Impl. */
class SlotCalendar {

    boolean free(String startsAt) {
        return !startsAt.isEmpty();
    }
}
