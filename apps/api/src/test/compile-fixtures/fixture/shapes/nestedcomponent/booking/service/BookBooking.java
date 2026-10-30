package fixture.shapes.nestedcomponent.booking.service;

/** A record that a response reaches only as a component of a dto record, written inside the use case. */
public class BookBooking {

    public record Slot(String startsAt) {}
}
