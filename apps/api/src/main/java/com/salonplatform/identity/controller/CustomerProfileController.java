package com.salonplatform.identity.controller;

import com.salonplatform.identity.dto.AccountDeletionRequest;
import com.salonplatform.identity.dto.CustomerProfileUpdateRequest;
import com.salonplatform.identity.dto.Export;
import com.salonplatform.identity.dto.Preferences;
import com.salonplatform.identity.dto.Profile;
import com.salonplatform.identity.service.CustomerProfileService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "identity")
@RequestMapping(path = "/api/v1/identity/customers/me", produces = MediaType.APPLICATION_JSON_VALUE)
class CustomerProfileController {
    private final CustomerProfileService profile;

    CustomerProfileController(CustomerProfileService profile) {
        this.profile = profile;
    }

    @GetMapping
    ResponseEntity<Profile> read() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profile.read());
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Profile> update(@Valid @RequestBody CustomerProfileUpdateRequest r) {
        var p = r.notificationPreferences();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(profile.update(new CustomerProfileService.Change(
                        r.version(),
                        r.name(),
                        r.email(),
                        r.birthday(),
                        r.preferredLanguage(),
                        new Preferences(p.sms(), p.whatsapp(), p.email()),
                        r.marketingConsent())));
    }

    @GetMapping("/export")
    ResponseEntity<Export> export() {
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
