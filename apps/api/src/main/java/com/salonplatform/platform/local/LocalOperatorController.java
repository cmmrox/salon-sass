package com.salonplatform.platform.local;

import com.salonplatform.platform.service.BootstrapFaults;
import com.salonplatform.platform.service.OperatorAccounts;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local operator fixtures (docs/architecture/operator-console.md, "Local operation"): loopback only,
 * {@code local} profile only, never in the OpenAPI document. Seeds explicit synthetic Admin/Support
 * accounts with supplied credentials (never echoed), resets their MFA and rate state, and sets a
 * controlled bootstrap failure. Salons, plans and other accounts are never touched.
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping("/local/operator")
class LocalOperatorController {

    private final OperatorAccounts accounts;
    private final LocalBootstrapFaults faults;

    LocalOperatorController(OperatorAccounts accounts, LocalBootstrapFaults faults) {
        this.accounts = accounts;
        this.faults = faults;
    }

    record SeedRequest(@NotEmpty @Size(max = 20) List<OperatorAccounts.AccountInput> accounts) {}

    record SeedResult(int accounts) {}

    record FaultRequest(String step) {}

    record FaultState(String step) {}

    @PostMapping("/seed")
    ResponseEntity<SeedResult> seed(@Valid @RequestBody SeedRequest request, HttpServletRequest http) {
        loopback(http);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new SeedResult(accounts.seed(request.accounts())));
    }

    @DeleteMapping("/fixtures")
    ResponseEntity<Void> reset(HttpServletRequest http) {
        loopback(http);
        accounts.resetFixtures();
        faults.set(null);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/bootstrap-failure")
    ResponseEntity<FaultState> fail(@RequestBody FaultRequest request, HttpServletRequest http) {
        loopback(http);
        BootstrapFaults.Step step = null;
        if (request.step() != null && !request.step().isBlank()) {
            try {
                step = BootstrapFaults.Step.valueOf(request.step().strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ApplicationProblemException(
                        400,
                        ErrorCode.BAD_REQUEST,
                        List.of(new FieldProblem("step", "must be owner, brand, receipt or null")));
            }
        }
        faults.set(step);
        return state();
    }

    @GetMapping("/bootstrap-failure")
    ResponseEntity<FaultState> current(HttpServletRequest http) {
        loopback(http);
        return state();
    }

    private ResponseEntity<FaultState> state() {
        BootstrapFaults.Step step = faults.current();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new FaultState(step == null ? null : step.name().toLowerCase(Locale.ROOT)));
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
