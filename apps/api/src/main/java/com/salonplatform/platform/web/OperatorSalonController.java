package com.salonplatform.platform.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.platform.application.Paging;
import com.salonplatform.platform.application.ProvisioningReceipts;
import com.salonplatform.platform.application.SalonDirectory;
import com.salonplatform.platform.application.SalonProvisioning;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
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
@RequestMapping(path = "/api/v1/operator/salons", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorSalonController {

    private final SalonDirectory salons;
    private final SalonProvisioning provisioning;

    OperatorSalonController(SalonDirectory salons, SalonProvisioning provisioning) {
        this.salons = salons;
        this.provisioning = provisioning;
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"name", "email", "password"})
    record SalonOwnerRequest(
            @Schema(maxLength = 120) String name,
            @Schema(maxLength = 254, format = "email") String email,

            @Schema(minLength = 15, maxLength = 128, format = "password")
            String password) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected("owner." + field);
        }
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"monthlyAmount", "paymentMethod"})
    record SalonSubscriptionRequest(
            @Schema(minimum = "0", maximum = "100000000") Long monthlyAmount,

            @Schema(allowableValues = {"card", "bank_transfer"})
            String paymentMethod) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected("subscription." + field);
        }
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {
                "name",
                "slug",
                "country",
                "currency",
                "timeZone",
                "defaultLanguage",
                "planId",
                "owner",
                "subscription"
            })
    record SalonRequest(
            @Schema(maxLength = 120) String name,
            @Schema(minLength = 3, maxLength = 30) String slug,
            @Schema(minLength = 2, maxLength = 2) String country,
            @Schema(minLength = 3, maxLength = 3) String currency,
            String timeZone,
            @Schema(allowableValues = {"en", "si", "ta"}) String defaultLanguage,

            @Schema(
                    types = {"string", "null"},
                    maxLength = 254)
            String contactEmail,

            @Schema(
                    types = {"string", "null"},
                    maxLength = 32)
            String contactPhone,

            @Schema(
                    types = {"string", "null"},
                    maxLength = 500)
            String address,

            UUID planId,
            SalonOwnerRequest owner,
            SalonSubscriptionRequest subscription) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @GetMapping
    ResponseEntity<SalonDirectory.SalonPage> listSalons(
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
    ResponseEntity<SalonDirectory.SalonDetail> getSalon(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(salons.get(OperatorRequests.id(id)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<SalonProvisioning.SalonCreated> createSalon(
            @Parameter(
                            name = ProvisioningReceipts.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = ProvisioningReceipts.HEADER, required = false)
                    String key,
            @RequestBody SalonRequest request) {
        SalonRequest r = request;
        SalonProvisioning.OwnerInput owner = r.owner() == null
                ? null
                : new SalonProvisioning.OwnerInput(
                        r.owner().name(), r.owner().email(), r.owner().password());
        SalonProvisioning.SubscriptionInput subscription = r.subscription() == null
                ? null
                : new SalonProvisioning.SubscriptionInput(
                        r.subscription().monthlyAmount(), r.subscription().paymentMethod());
        var created = provisioning.create(
                key,
                new SalonProvisioning.SalonInput(
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
