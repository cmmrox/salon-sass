package fixture.shapes.nestedcomponent.booking.controller;

import fixture.shapes.nestedcomponent.booking.dto.SlotPage;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SlotController {

    @GetMapping("/slots")
    public List<SlotPage> slots() {
        return List.of();
    }
}
