package com.salonplatform.architecture.fixture.transactionalinterface.booking.service;

import jakarta.transaction.Transactional;

/** A transaction declared on an interface method. */
public interface PaymentService {

    @Transactional
    void pay();
}
