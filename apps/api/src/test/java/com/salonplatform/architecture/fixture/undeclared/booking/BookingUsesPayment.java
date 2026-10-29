package com.salonplatform.architecture.fixture.undeclared.booking;

import com.salonplatform.architecture.fixture.undeclared.catalogue.Services;
import com.salonplatform.architecture.fixture.undeclared.payment.Payments;

/** Uses payment's provided interface, which booking does not declare. */
public class BookingUsesPayment {

    Services services;

    Payments payments;
}
