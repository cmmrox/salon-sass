package fixture.shapes.othermodule.booking.controller;

import fixture.shapes.othermodule.payment.dto.Receipt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReceiptController {

    @GetMapping("/receipts")
    public Receipt receipt() {
        return new Receipt("R-1");
    }
}
