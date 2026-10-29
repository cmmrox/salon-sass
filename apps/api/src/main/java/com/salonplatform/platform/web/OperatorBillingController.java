package com.salonplatform.platform.web;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.platform.application.InvoiceBilling;
import com.salonplatform.platform.application.Paging;
import com.salonplatform.platform.application.ProvisioningReceipts;
import com.salonplatform.platform.application.SalonDirectory;
import com.salonplatform.platform.application.SubscriptionBilling;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * An Admin's subscription billing for one salon (F03-S02): the subscription, starting one for an
 * unbilled salon, changing its amount or method, moving the salon's plan, its invoices and recorded
 * bank transfers. Console host only, as every operator route.
 */
@RestController
@RequestMapping(path = "/api/v1/operator", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorBillingController {

    private final SubscriptionBilling subscriptions;
    private final InvoiceBilling invoices;

    OperatorBillingController(SubscriptionBilling subscriptions, InvoiceBilling invoices) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"monthlyAmount", "paymentMethod"})
    record StartSubscriptionRequest(
            @Schema(minimum = "0", maximum = "100000000") Long monthlyAmount,

            @Schema(allowableValues = {"card", "bank_transfer"})
            String paymentMethod,

            @Schema(
                    description = "Required, and only allowed, when the salon has no currency of its own",
                    minLength = 3,
                    maxLength = 3)
            String currency) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"version"})
    record ChangeSubscriptionRequest(
            @Schema(minimum = "0") Long version,
            @Schema(minimum = "0", maximum = "100000000") Long monthlyAmount,

            @Schema(allowableValues = {"card", "bank_transfer"})
            String paymentMethod) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"version", "planId"})
    record MovePlanRequest(@Schema(minimum = "0") Long version, java.util.UUID planId) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @Schema(
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
            requiredProperties = {"reference", "amount", "receivedOn"})
    record RecordTransferRequest(
            @Schema(maxLength = 120) String reference,
            @Schema(minimum = "0", maximum = "100000000") Long amount,
            @Schema(format = "date") String receivedOn) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw OperatorRequests.unexpected(field);
        }
    }

    @GetMapping("/salons/{id}/subscription")
    ResponseEntity<SubscriptionBilling.SubscriptionView> getSubscription(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.get(OperatorRequests.id(id)));
    }

    @PostMapping(path = "/salons/{id}/subscription", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<SubscriptionBilling.SubscriptionView> startSubscription(
            @PathVariable String id,
            @Parameter(
                            name = ProvisioningReceipts.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = ProvisioningReceipts.HEADER, required = false)
                    String key,
            @RequestBody StartSubscriptionRequest request) {
        var started = subscriptions.start(
                OperatorRequests.id(id),
                key,
                new SubscriptionBilling.StartInput(
                        request.monthlyAmount(), request.paymentMethod(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(started.subscription());
    }

    @PatchMapping(path = "/salons/{id}/subscription", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubscriptionBilling.SubscriptionView> changeSubscription(
            @PathVariable String id, @RequestBody ChangeSubscriptionRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.change(
                        OperatorRequests.id(id),
                        new SubscriptionBilling.ChangeInput(
                                request.version(), request.monthlyAmount(), request.paymentMethod())));
    }

    @PutMapping(path = "/salons/{id}/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SalonDirectory.SalonDetail> movePlan(@PathVariable String id, @RequestBody MovePlanRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.movePlan(
                        OperatorRequests.id(id),
                        new SubscriptionBilling.PlanMoveInput(request.version(), request.planId())));
    }

    @GetMapping("/salons/{id}/invoices")
    ResponseEntity<InvoiceBilling.InvoicePage> listInvoices(
            @PathVariable String id,
            @Parameter(schema = @Schema(type = "integer", minimum = "0", maximum = "100000", defaultValue = "0"))
                    @RequestParam(required = false)
                    String page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
                    @RequestParam(required = false)
                    String size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invoices.list(OperatorRequests.id(id), Paging.of(page, size, null)));
    }

    @GetMapping("/invoices/{id}")
    ResponseEntity<InvoiceBilling.InvoiceDetail> getInvoice(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoices.get(OperatorRequests.id(id)));
    }

    @PostMapping(path = "/invoices/{id}/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<InvoiceBilling.PaymentView> recordTransfer(
            @PathVariable String id,
            @Parameter(
                            name = ProvisioningReceipts.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = ProvisioningReceipts.HEADER, required = false)
                    String key,
            @RequestBody RecordTransferRequest request) {
        var recorded = invoices.recordTransfer(
                OperatorRequests.id(id),
                key,
                new InvoiceBilling.TransferInput(request.reference(), request.amount(), request.receivedOn()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(recorded.payment());
    }
}
