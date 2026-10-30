package com.salonplatform.platform.controller;

import com.salonplatform.platform.domain.IdempotencyKey;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.SalonCreated;
import com.salonplatform.platform.dto.SalonDetail;
import com.salonplatform.platform.dto.SalonPage;
import com.salonplatform.platform.dto.SalonRequest;
import com.salonplatform.platform.service.SalonDirectoryService;
import com.salonplatform.platform.service.SalonProvisioningService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The salon directory, salon detail and idempotent salon/first-Owner provisioning. */
@RestController
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/operator/salons", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorSalonController {

    private final SalonDirectoryService salons;
    private final SalonProvisioningService provisioning;

    OperatorSalonController(SalonDirectoryService salons, SalonProvisioningService provisioning) {
        this.salons = salons;
        this.provisioning = provisioning;
    }

    @GetMapping
    ResponseEntity<SalonPage> listSalons(
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
                    @RequestParam(required = false)
                    String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String size,
            @Parameter(schema = @Schema(type = "string", maxLength = 120)) @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(salons.list(Paging.of(page, size, q)));
    }

    @GetMapping("/{id}")
    ResponseEntity<SalonDetail> getSalon(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(salons.get(OperatorRequests.id(id)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<SalonCreated> createSalon(
            @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = IdempotencyKey.HEADER, required = false)
                    String key,
            @RequestBody SalonRequest request) {
        SalonRequest r = request;
        SalonProvisioningService.OwnerInput owner = r.owner() == null
                ? null
                : new SalonProvisioningService.OwnerInput(
                        r.owner().name(), r.owner().email(), r.owner().password());
        SalonProvisioningService.SubscriptionInput subscription = r.subscription() == null
                ? null
                : new SalonProvisioningService.SubscriptionInput(
                        r.subscription().monthlyAmount(), r.subscription().paymentMethod());
        var created = provisioning.create(
                key,
                new SalonProvisioningService.SalonInput(
                        r.name(),
                        r.slug(),
                        r.country(),
                        r.currency(),
                        r.timeZone(),
                        r.defaultLanguage(),
                        r.contactEmail(),
                        r.contactPhone(),
                        r.address(),
                        r.planId(),
                        owner,
                        subscription));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(created.salon());
    }
}
