package fixture.persistence.clean.booking.service;

import fixture.persistence.clean.booking.entity.Booking;
import fixture.persistence.clean.booking.repository.BookingRepository;
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
