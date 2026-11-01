package com.salonplatform.architecture.fixture.serviceforothermodule.booking.service.impl;

import com.salonplatform.architecture.fixture.serviceforothermodule.payment.PaymentGateway;
import org.springframework.stereotype.Service;

/**
 * A service implementing another module's provided interface, which is no in-process contract declared for it to
 * implement: still refused.
 */
@Service
class PaymentGatewayImpl implements PaymentGateway {}
