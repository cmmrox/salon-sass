package fixture.shapes.nestedresponse.booking.controller;

import fixture.shapes.nestedresponse.booking.service.BookBooking;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SlotController {

    private final BookBooking booking = new BookBooking();

    @PostMapping("/slots")
    public ResponseEntity<BookBooking.Slot> book() {
        return ResponseEntity.ok(booking.book());
    }
}
