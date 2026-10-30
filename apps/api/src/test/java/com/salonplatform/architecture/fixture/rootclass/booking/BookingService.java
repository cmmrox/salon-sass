package com.salonplatform.architecture.fixture.rootclass.booking;

import org.springframework.stereotype.Service;

/** A provided service written as a concrete bean in the root package, where only its interface belongs. */
@Service
public class BookingService {

    public boolean available() {
        return true;
    }
}
