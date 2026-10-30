package com.salonplatform.identity.local;

import com.salonplatform.identity.dto.CapturedMessage;
import com.salonplatform.identity.dto.DeliveryView;
import com.salonplatform.identity.dto.ProviderControl;
import com.salonplatform.identity.service.CustomerAccessService;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local")
@Hidden
@RequestMapping("/local/identity")
class LocalCustomerController {
    private final CustomerAccessService customers;
    private final CustomerMessageCapture capture;

    LocalCustomerController(CustomerAccessService customers, CustomerMessageCapture capture) {
        this.customers = customers;
        this.capture = capture;
    }

    @GetMapping("/captures/{id}")
    ResponseEntity<CapturedMessage> capture(@PathVariable UUID id, HttpServletRequest request) {
        loopback(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(capture.capture(id));
    }

    @GetMapping("/deliveries/{id}")
    ResponseEntity<DeliveryView> delivery(@PathVariable UUID id, HttpServletRequest request) {
        loopback(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DeliveryView.from(customers.delivery(id)));
    }

    @PutMapping("/providers/{channel}")
    ResponseEntity<Void> control(
            @PathVariable String channel, @Valid @RequestBody ProviderControl control, HttpServletRequest request) {
        loopback(request);
        capture.control(channel, control.failures(), control.delayMs());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/customers")
    ResponseEntity<Void> reset(HttpServletRequest request) {
        loopback(request);
        customers.resetCustomer();
        capture.clear();
        return ResponseEntity.noContent().build();
    }

    private static void loopback(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) throw new NotFoundException();
        } catch (java.net.UnknownHostException ex) {
            throw new NotFoundException();
        }
    }
}
