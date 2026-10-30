package com.salonplatform.platform.local;

import com.salonplatform.branding.inprocess.BrandProvisioning;
import com.salonplatform.platform.adapter.BillingProviderKey;
import com.salonplatform.platform.domain.Slugs;
import com.salonplatform.platform.dto.BillingInterruptRequest;
import com.salonplatform.platform.dto.BillingInterruptState;
import com.salonplatform.platform.dto.BillingJobPass;
import com.salonplatform.platform.dto.CapturedEmails;
import com.salonplatform.platform.dto.OperatorActiveRequest;
import com.salonplatform.platform.dto.ProviderEventSignature;
import com.salonplatform.platform.dto.ProviderOutcomeRequest;
import com.salonplatform.platform.dto.ProviderOutcomeState;
import com.salonplatform.platform.dto.UnbilledRequest;
import com.salonplatform.platform.dto.UnbilledSalon;
import com.salonplatform.platform.repository.OperatorUserStore;
import com.salonplatform.platform.repository.SalonStore;
import com.salonplatform.platform.service.BillingFaults;
import com.salonplatform.platform.service.BillingJobService;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03-S02's local test hooks (docs/architecture/operator-console.md, "Local test hooks"): loopback
 * only, {@code local} profile only, never in the OpenAPI document. They set the fake provider's charge
 * outcome per salon and sign arbitrary event bodies with the local provider key, list and clear
 * captured emails, run one billing pass now at the test clock's instant, arm a one-shot interruption
 * of the billing job, create an unbilled legacy salon, and disable or re-enable a hook-created
 * fixture operator. Alpha, Beta and Gamma are never created or billed by them.
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping("/local/operator")
class LocalBillingController {

    private final LocalBillingProvider provider;
    private final LocalEmailCapture emails;
    private final LocalBillingFaults faults;
    private final BillingJobService job;
    private final BillingProviderKey key;
    private final PlatformDatabase database;
    private final SalonStore salons;
    private final OperatorUserStore users;
    private final BrandProvisioning branding;
    private final Clock clock;
    private final String platformDomain;

    LocalBillingController(
            LocalBillingProvider provider,
            LocalEmailCapture emails,
            LocalBillingFaults faults,
            BillingJobService job,
            BillingProviderKey key,
            PlatformDatabase database,
            SalonStore salons,
            OperatorUserStore users,
            BrandProvisioning branding,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.provider = provider;
        this.emails = emails;
        this.faults = faults;
        this.job = job;
        this.key = key;
        this.database = database;
        this.salons = salons;
        this.users = users;
        this.branding = branding;
        this.clock = clock;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /** The fake provider's outcome for every later charge of this salon: success (default) or failure. */
    @PutMapping("/billing/provider-outcomes/{salonId}")
    ResponseEntity<ProviderOutcomeState> setOutcome(
            @PathVariable UUID salonId, @RequestBody ProviderOutcomeRequest request, HttpServletRequest http) {
        loopback(http);
        if (request == null || !List.of("success", "failure").contains(request.outcome())) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("outcome", "must be success or failure")));
        }
        provider.outcome(salonId, request.outcome());
        return ok(new ProviderOutcomeState(salonId, request.outcome()));
    }

    @GetMapping("/billing/provider-outcomes/{salonId}")
    ResponseEntity<ProviderOutcomeState> outcome(@PathVariable UUID salonId, HttpServletRequest http) {
        loopback(http);
        return ok(new ProviderOutcomeState(salonId, provider.outcome(salonId)));
    }

    /** Signs the exact request body, whatever it is, with the local provider key. */
    @PostMapping("/billing/provider-events/signature")
    ResponseEntity<ProviderEventSignature> sign(@RequestBody(required = false) byte[] body, HttpServletRequest http) {
        loopback(http);
        return ok(new ProviderEventSignature(
                BillingProviderKey.HEADER,
                key.sign(body == null ? new byte[0] : body).orElseThrow()));
    }

    @GetMapping("/emails")
    ResponseEntity<CapturedEmails> emails(@RequestParam(required = false) String recipient, HttpServletRequest http) {
        loopback(http);
        return ok(new CapturedEmails(emails.list(recipient)));
    }

    @DeleteMapping("/emails")
    ResponseEntity<Void> clearEmails(@RequestParam(required = false) String recipient, HttpServletRequest http) {
        loopback(http);
        emails.clear(recipient);
        return ResponseEntity.noContent().build();
    }

    /** One billing pass, now, at the application clock's instant (the test clock when it is set). */
    @PostMapping("/billing/run")
    ResponseEntity<BillingJobPass> run(HttpServletRequest http) {
        loopback(http);
        return ok(job.run(clock.instant()));
    }

    @PutMapping("/billing/interrupt")
    ResponseEntity<BillingInterruptState> interrupt(
            @RequestBody BillingInterruptRequest request, HttpServletRequest http) {
        loopback(http);
        BillingFaults.Point point = null;
        if (request != null && request.point() != null && !request.point().isBlank()) {
            if (!"after-invoice-issue".equals(request.point())) {
                throw new ApplicationProblemException(
                        400,
                        ErrorCode.BAD_REQUEST,
                        List.of(new FieldProblem("point", "must be after-invoice-issue or null")));
            }
            point = BillingFaults.Point.AFTER_INVOICE_ISSUE;
        }
        faults.arm(point);
        return interruptState();
    }

    @GetMapping("/billing/interrupt")
    ResponseEntity<BillingInterruptState> interruptState(HttpServletRequest http) {
        loopback(http);
        return interruptState();
    }

    private ResponseEntity<BillingInterruptState> interruptState() {
        return ok(new BillingInterruptState(faults.current() == null ? null : "after-invoice-issue"));
    }

    /**
     * A synthetic legacy salon, as the salons created before F03 are: active, no subscription, no plan
     * and null profile fields, with only the published name and neutral colour that workspace routing
     * needs. Its staff are seeded with the identity hooks.
     */
    @PostMapping("/unbilled-salons")
    ResponseEntity<UnbilledSalon> unbilled(
            @RequestBody(required = false) UnbilledRequest request, HttpServletRequest http) {
        loopback(http);
        String slug =
                request == null || request.slug() == null || request.slug().isBlank()
                        ? "legacy-"
                                + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                        : Slugs.normalize(request.slug());
        if (!Slugs.wellFormed(slug)
                || Slugs.RESERVED.contains(slug)
                || List.of("alpha", "beta", "gamma").contains(slug)) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("slug", "must be a free, well-formed, unreserved slug")));
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        try {
            database.inTransaction(status -> {
                salons.createUnbilled(id, slug, now);
                branding.bootstrapBrand(id, "Legacy fixture " + slug, now);
                return null;
            });
        } catch (DuplicateKeyException ex) {
            throw new ApplicationProblemException(409, ErrorCode.SLUG_TAKEN);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new UnbilledSalon(id, slug, slug + "." + platformDomain));
    }

    /** Disables or re-enables an operator created by the fixture hook; any other operator is not found. */
    @PutMapping("/operators/active")
    ResponseEntity<Void> operatorActive(@RequestBody OperatorActiveRequest request, HttpServletRequest http) {
        loopback(http);
        if (request == null || request.email() == null || request.active() == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("email", "and active are required")));
        }
        boolean changed = database.inTransaction(status -> users.setFixtureActive(
                request.email().strip().toLowerCase(Locale.ROOT), request.active(), clock.instant()));
        if (!changed) {
            throw new NotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    private static void loopback(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                throw new NotFoundException();
            }
        } catch (UnknownHostException ex) {
            throw new NotFoundException();
        }
    }
}
