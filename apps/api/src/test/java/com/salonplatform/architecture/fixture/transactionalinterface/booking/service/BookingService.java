package com.salonplatform.architecture.fixture.transactionalinterface.booking.service;

import org.springframework.transaction.annotation.Transactional;

/** A transaction declared on the interface instead of the implementation. */
@Transactional
public interface BookingService {}
