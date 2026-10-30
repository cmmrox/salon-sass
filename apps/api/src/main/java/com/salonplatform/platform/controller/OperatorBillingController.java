package com.salonplatform.platform.controller;

import com.salonplatform.platform.domain.IdempotencyKey;
import com.salonplatform.platform.domain.Paging;
import com.salonplatform.platform.dto.ChangeSubscriptionRequest;
import com.salonplatform.platform.dto.InvoiceDetail;
import com.salonplatform.platform.dto.InvoicePage;
import com.salonplatform.platform.dto.MovePlanRequest;
import com.salonplatform.platform.dto.PaymentView;
import com.salonplatform.platform.dto.RecordTransferRequest;
import com.salonplatform.platform.dto.SalonDetail;
import com.salonplatform.platform.dto.StartSubscriptionRequest;
import com.salonplatform.platform.dto.SubscriptionView;
import com.salonplatform.platform.service.InvoiceBillingService;
import com.salonplatform.platform.service.SubscriptionBillingService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "platform")
@RequestMapping(path = "/api/v1/operator", produces = MediaType.APPLICATION_JSON_VALUE)
class OperatorBillingController {

    private final SubscriptionBillingService subscriptions;
    private final InvoiceBillingService invoices;

    OperatorBillingController(SubscriptionBillingService subscriptions, InvoiceBillingService invoices) {
        this.subscriptions = subscriptions;
        this.invoices = invoices;
    }

    @GetMapping("/salons/{id}/subscription")
    ResponseEntity<SubscriptionView> getSubscription(@PathVariable String id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.get(OperatorRequests.id(id)));
    }

    @PostMapping(path = "/salons/{id}/subscription", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<SubscriptionView> startSubscription(
            @PathVariable String id,
            @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = IdempotencyKey.HEADER, required = false)
                    String key,
            @RequestBody StartSubscriptionRequest request) {
        var started = subscriptions.start(
                OperatorRequests.id(id),
                key,
                new SubscriptionBillingService.StartInput(
                        request.monthlyAmount(), request.paymentMethod(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(started.subscription());
    }

    @PatchMapping(path = "/salons/{id}/subscription", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SubscriptionView> changeSubscription(
            @PathVariable String id, @RequestBody ChangeSubscriptionRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.change(
                        OperatorRequests.id(id),
                        new SubscriptionBillingService.ChangeInput(
                                request.version(), request.monthlyAmount(), request.paymentMethod())));
    }

    @PutMapping(path = "/salons/{id}/plan", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<SalonDetail> movePlan(@PathVariable String id, @RequestBody MovePlanRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(subscriptions.movePlan(
                        OperatorRequests.id(id),
                        new SubscriptionBillingService.PlanMoveInput(request.version(), request.planId())));
    }

    @GetMapping("/salons/{id}/invoices")
    ResponseEntity<InvoicePage> listInvoices(
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
    ResponseEntity<InvoiceDetail> getInvoice(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoices.get(OperatorRequests.id(id)));
    }

    @PostMapping(path = "/invoices/{id}/payments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<PaymentView> recordTransfer(
            @PathVariable String id,
            @Parameter(
                            name = IdempotencyKey.HEADER,
                            in = ParameterIn.HEADER,
                            required = true,
                            schema = @Schema(type = "string", format = "uuid"))
                    @RequestHeader(name = IdempotencyKey.HEADER, required = false)
                    String key,
            @RequestBody RecordTransferRequest request) {
        var recorded = invoices.recordTransfer(
                OperatorRequests.id(id),
                key,
                new InvoiceBillingService.TransferInput(request.reference(), request.amount(), request.receivedOn()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(recorded.payment());
    }
}
