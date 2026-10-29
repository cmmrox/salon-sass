package com.salonplatform.shared.local;

import com.salonplatform.shared.api.NotFoundException;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /local/probes}, on a salon's hostname: {@code POST} writes a probe, {@code GET /{id}} shows
 * it. A probe of another salon is not found, exactly as one that does not exist.
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping(path = "/local/probes", produces = MediaType.APPLICATION_JSON_VALUE)
class ProbeController {

    private final Probes probes;

    ProbeController(Probes probes) {
        this.probes = probes;
    }

    /** What the probe's provider is told to do. Every field is optional. */
    record CreateProbe(
            @Min(0) @Max(50) Integer failures,
            @Min(0) @Max(60000) Long delayMs,
            Boolean abort) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Probes.ProbeView> create(@Valid @RequestBody(required = false) CreateProbe body) {
        CreateProbe asked = body == null ? new CreateProbe(null, null, null) : body;
        Probes.ProbeView probe = probes.create(new Probes.Request(
                asked.failures() == null ? 0 : asked.failures(),
                asked.delayMs() == null ? 0 : asked.delayMs(),
                Boolean.TRUE.equals(asked.abort())));
        return ResponseEntity.created(URI.create("/local/probes/" + probe.id())).body(probe);
    }

    @GetMapping("/{id}")
    Probes.ProbeView read(@PathVariable String id) {
        return probes.find(parse(id)).orElseThrow(NotFoundException::new);
    }

    /** An id that is not a UUID names nothing: not found, like any other. */
    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            throw new NotFoundException();
        }
    }
}
