package fixture.shapes.nestedresponse.booking.service;

/** A response record written inside the use case instead of in dto. */
public class BookBooking {

    public record Slot(String startsAt) {}

    public Slot book() {
        return new Slot("09:00");
    }
}
