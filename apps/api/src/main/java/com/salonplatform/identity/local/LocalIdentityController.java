package com.salonplatform.identity.local;

import com.salonplatform.identity.application.StaffAccess;
import com.salonplatform.shared.api.NotFoundException;

import io.swagger.v3.oas.annotations.Hidden;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.List;

/** Loopback synthetic-only fixtures. Submitted credentials are never returned. */
@RestController
@Profile("local")
@Hidden
@RequestMapping("/local/identity")
class LocalIdentityController {
    private final StaffAccess access;

    LocalIdentityController(StaffAccess access) {
        this.access = access;
    }

    record SeedRequest(@NotEmpty @Size(max = 20) List<StaffAccess.SeedAccount> accounts) {}

    record SeedResult(int accounts) {}

    @PostMapping("/seed")
    ResponseEntity<SeedResult> seed(
            @Valid @RequestBody SeedRequest request, HttpServletRequest http) {
        loopback(http);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new SeedResult(access.seed(request.accounts()).size()));
    }

    @DeleteMapping
    ResponseEntity<Void> reset(HttpServletRequest http) {
        loopback(http);
        access.reset();
        return ResponseEntity.noContent().build();
    }

    private static void loopback(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())
                throw new NotFoundException();
        } catch (java.net.UnknownHostException ex) {
            throw new NotFoundException();
        }
    }
}
