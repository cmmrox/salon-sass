package fixture.shapes.nestedrequest.booking.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** A request record written inside the controller instead of in dto. */
@RestController
public class BookingController {

    record BookRequest(String startsAt) {}

    @PostMapping("/bookings")
    public ResponseEntity<Void> book(@RequestBody BookRequest request) {
        return ResponseEntity.noContent().build();
    }
}
