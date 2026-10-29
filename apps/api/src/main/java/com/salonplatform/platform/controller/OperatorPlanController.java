package com.salonplatform.platform.controller;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.platform.service.Paging;
import com.salonplatform.platform.service.PlanCatalogue;
import com.salonplatform.platform.service.ProvisioningReceipts;
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

/** Immutable plan definitions: list, read and idempotent creation. */
@RestController
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/operator/plans", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorPlanController {

    private final PlanCatalogue plans;

    OperatorPlanController(PlanCatalogue plans) {
        this.plans = plans;
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {
                "key",
                "name",
                "staffSeats",
                "branches",
                "messageAllowance",
                "customDomain",
                "dedicatedApp"
            })
    record PlanRequest(
            @Schema(minLength = 3, maxLength = 30) String key,
            @Schema(maxLength = 120) String name,
            @Schema(minimum = "1", maximum = "1000000") Integer staffSeats,
            @Schema(minimum = "1", maximum = "1000000") Integer branches,
            @Schema(minimum = "0", maximum = "1000000") Integer messageAllowance,
            Boolean customDomain,
            Boolean dedicatedApp) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @GetMapping
    ResponseEntity<PlanCatalogue.PlanPage> listPlans(
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
                    @RequestParam(required = false)
                    String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String size,
            @Parameter(schema = @Schema(type = "string", maxLength = 120)) @RequestParam(required = false) String q) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.list(Paging.of(page, size, q)));
    }

    @GetMapping("/{id}")
    ResponseEntity<PlanCatalogue.PlanDefinition> getPlan(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.get(OperatorRequests.id(id)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<PlanCatalogue.PlanDefinition> createPlan(
            @Parameter(
                            name = ProvisioningReceipts.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = ProvisioningReceipts.HEADER, required = false)
                    String key,
            @RequestBody PlanRequest request) {
        var created = plans.create(
                key,
                new PlanCatalogue.PlanInput(
                        request.key(),
                        request.name(),
                        request.staffSeats(),
                        request.branches(),
                        request.messageAllowance(),
                        request.customDomain(),
                        request.dedicatedApp()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(created.plan());
    }
}
