package com.salonplatform.architecture.fixture.controllerusesimpl.booking.controller;

import com.salonplatform.architecture.fixture.controllerusesimpl.booking.service.impl.BookingServiceImpl;
import org.springframework.web.bind.annotation.RestController;

/** A controller that injects the implementation instead of the interface. */
@RestController
class SlotBookingController {

    BookingServiceImpl bookings;
}
