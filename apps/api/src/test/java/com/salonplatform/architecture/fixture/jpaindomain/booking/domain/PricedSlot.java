package com.salonplatform.architecture.fixture.jpaindomain.booking.domain;

import jakarta.persistence.Column;

/** A value object that carries a JPA mapping, which belongs on an entity. */
public class PricedSlot {

    @Column(name = "price_minor")
    long priceMinor;
}
