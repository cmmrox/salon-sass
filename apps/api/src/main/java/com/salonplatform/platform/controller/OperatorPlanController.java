package com.salonplatform.platform.controller;

import com.salonplatform.platform.domain.IdempotencyKey;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.PlanDefinition;
import com.salonplatform.platform.dto.PlanPage;
import com.salonplatform.platform.dto.PlanRequest;
import com.salonplatform.platform.service.PlanCatalogueService;
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

    private final PlanCatalogueService plans;

    OperatorPlanController(PlanCatalogueService plans) {
        this.plans = plans;
    }

    @GetMapping
    ResponseEntity<PlanPage> listPlans(
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
    ResponseEntity<PlanDefinition> getPlan(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(plans.get(OperatorRequests.id(id)));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<PlanDefinition> createPlan(
            @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = IdempotencyKey.HEADER, required = false)
                    String key,
            @RequestBody PlanRequest request) {
        var created = plans.create(
                key,
                new PlanCatalogueService.PlanInput(
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
