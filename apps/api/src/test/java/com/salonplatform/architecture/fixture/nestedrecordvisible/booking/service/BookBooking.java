package com.salonplatform.architecture.fixture.nestedrecordvisible.booking.service;

/** A record that only its own class uses, left visible to the rest of the package. */
public class BookBooking {

    record Window(String from, String to) {}

    public String describe() {
        Window window = new Window("09:00", "10:00");
        return window.from() + "-" + window.to();
    }
}
