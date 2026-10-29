package fixture.shapes.nestedcomponent.booking.dto;

import fixture.shapes.nestedcomponent.booking.service.BookBooking;
import java.util.List;

public record SlotPage(List<BookBooking.Slot> items) {}
