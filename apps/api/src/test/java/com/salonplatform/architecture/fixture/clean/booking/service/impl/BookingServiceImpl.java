package com.salonplatform.architecture.fixture.clean.booking.service.impl;

import com.salonplatform.architecture.fixture.clean.booking.service.BookingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The implementation, which carries the service and its transaction. */
@Service
@Transactional
class BookingServiceImpl implements BookingService {

    private final SlotCalendar calendar = new SlotCalendar();

    @Override
    public String book(String startsAt) {
        return calendar.free(startsAt) ? startsAt : "";
    }
}
