package com.salonplatform.architecture.fixture.servicewithoutinterface.booking.service.impl;

import org.springframework.stereotype.Service;

/** A service with no interface: its callers can only inject the class. */
@Service
class SlotFinder {}
