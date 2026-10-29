package fixture.persistence.clean.booking.application;

import fixture.persistence.clean.booking.domain.Booking;
import fixture.persistence.clean.booking.infrastructure.BookingRepository;
import java.util.UUID;

public class BookBooking {

    private final BookingRepository bookings;

    public BookBooking(BookingRepository bookings) {
        this.bookings = bookings;
    }

    public UUID hold() {
        return bookings.save(Booking.held(UUID.randomUUID())).getId();
    }
}
