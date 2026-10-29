package fixture.shapes.clean.booking.controller;

import fixture.shapes.clean.booking.dto.BookRequest;
import fixture.shapes.clean.booking.dto.Slot;
import fixture.shapes.clean.booking.dto.SlotPage;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Reads and returns only top-level records of its own module's dto, at every depth. */
@RestController
public class SlotController {

    @GetMapping("/slots")
    public ResponseEntity<SlotPage> slots() {
        return ResponseEntity.ok(new SlotPage(List.of()));
    }

    @PostMapping("/slots")
    public Slot book(@RequestBody BookRequest request) {
        return new Slot(request.startsAt());
    }
}
