package com.salonplatform.platform.api;

import java.util.UUID;

import com.salonplatform.platform.application.InvoiceBilling;
import com.salonplatform.platform.application.Paging;
import com.salonplatform.platform.application.SalonBilling;
import com.salonplatform.shared.api.ApiPaths;
import com.salonplatform.shared.api.NotFoundException;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A salon's plan, entitlements, subscription state and invoices, for its own staff on its own host
 * (F03-S02). Reads only.
 */
@RestController
@RequestMapping(path = ApiPaths.V1 + "/salon", produces = MediaType.APPLICATION_JSON_VALUE)
class SalonBillingController {

    private final SalonBilling billing;

    SalonBillingController(SalonBilling billing) {
        this.billing = billing;
    }

    @GetMapping("/entitlements")
    ResponseEntity<SalonBilling.Entitlements> entitlements() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(billing.entitlements());
    }

    @GetMapping("/invoices")
    ResponseEntity<InvoiceBilling.InvoicePage> invoices(
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
            @RequestParam(required = false) String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(required = false) String size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(billing.invoices(Paging.of(page, size, null)));
    }

    @GetMapping("/invoices/{id}")
    ResponseEntity<InvoiceBilling.InvoiceDetail> invoice(@PathVariable String id) {
        UUID invoice;
        try {
            invoice = UUID.fromString(id);
        }
        catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(billing.invoice(invoice));
    }
}
