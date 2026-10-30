package com.salonplatform.architecture.fixture.clean.booking.controller;

import com.salonplatform.architecture.fixture.clean.booking.service.BookingService;
import org.springframework.web.bind.annotation.RestController;

/** A controller injects the service's interface. */
@RestController
class SlotController {

    BookingService bookings;
}
