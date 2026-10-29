package com.salonplatform.identity.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.salonplatform.identity.application.CustomerProfile;
import com.salonplatform.shared.api.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(path = "/api/v1/identity/customers/me", produces = MediaType.APPLICATION_JSON_VALUE)
class CustomerProfileController {
    private final CustomerProfile profile;

    CustomerProfileController(CustomerProfile profile) {
        this.profile = profile;
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties = io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    record NotificationPreferencesRequest(
            @NotNull Boolean sms,
            @NotNull Boolean whatsapp,
            @NotNull Boolean email) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw unexpected(field);
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties = io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    record CustomerProfileUpdateRequest(
            @NotNull @PositiveOrZero Long version,
            @NotBlank String name,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    format = "email")
            String email,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    format = "date")
            LocalDate birthday,

            @NotBlank String preferredLanguage,
            @NotNull @Valid NotificationPreferencesRequest notificationPreferences,
            @NotNull Boolean marketingConsent) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw unexpected(field);
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(
            additionalProperties = io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
    record AccountDeletionRequest(@NotNull Boolean confirm) {
        @JsonAnySetter
        void unknown(String field, Object ignored) {
            throw unexpected(field);
        }
    }

    private static ApplicationProblemException unexpected(String field) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, "is not allowed")));
    }

    @GetMapping
    ResponseEntity<CustomerProfile.Profile> read() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile.read());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CustomerProfile.Profile> update(@Valid @RequestBody CustomerProfileUpdateRequest r) {
        var p = r.notificationPreferences();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(profile.update(new CustomerProfile.Change(
                        r.version(),
                        r.name(),
                        r.email(),
                        r.birthday(),
                        r.preferredLanguage(),
                        new CustomerProfile.Preferences(p.sms(), p.whatsapp(), p.email()),
                        r.marketingConsent())));
    }

    @GetMapping("/export")
    ResponseEntity<CustomerProfile.Export> export() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customer-data.json\"")
                .body(profile.export());
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> delete(@Valid @RequestBody AccountDeletionRequest r) {
        profile.delete(r.confirm());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
