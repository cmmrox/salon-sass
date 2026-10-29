package com.salonplatform.identity.local;

import com.salonplatform.identity.application.CustomerAccess;
import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.outbox.OutboxMessageView;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
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
    private final CustomerAccess customers;
    private final CustomerMessageCapture capture;

    LocalCustomerController(CustomerAccess customers, CustomerMessageCapture capture) {
        this.customers = customers;
        this.capture = capture;
    }

    record Control(
            @Min(0) @Max(50) int failures,
            @Min(0) @Max(60000) long delayMs) {}

    @GetMapping("/captures/{id}")
    ResponseEntity<CustomerMessageCapture.Captured> capture(@PathVariable UUID id, HttpServletRequest request) {
        loopback(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(capture.capture(id));
    }

    @GetMapping("/deliveries/{id}")
    ResponseEntity<OutboxMessageView> delivery(@PathVariable UUID id, HttpServletRequest request) {
        loopback(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(customers.delivery(id));
    }

    @PutMapping("/providers/{channel}")
    ResponseEntity<Void> control(
            @PathVariable String channel, @Valid @RequestBody Control control, HttpServletRequest request) {
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
