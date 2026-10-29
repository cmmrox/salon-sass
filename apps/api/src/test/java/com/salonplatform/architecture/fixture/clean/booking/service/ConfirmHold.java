package com.salonplatform.architecture.fixture.clean.booking.service;

public class ConfirmHold {

    HoldSlot holds;

    String confirm(String startsAt) {
        return holds.hold(startsAt) instanceof HoldSlot.Held held ? held.startsAt() : "";
    }
}
