package com.salonplatform.shared.local;

import com.salonplatform.shared.local.dto.ClockState;
import com.salonplatform.shared.local.dto.SetClock;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /local/clock}: read the application's clock, set it to an instant (it runs on from there),
 * and return it to real time. On any hostname.
 */
@RestController
@Profile("local")
@Hidden
@RequestMapping(path = "/local/clock", produces = MediaType.APPLICATION_JSON_VALUE)
class LocalClockController {

    private final TestClock clock;

    LocalClockController(TestClock clock) {
        this.clock = clock;
    }

    @GetMapping
    ClockState read() {
        return state();
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    ClockState set(@Valid @RequestBody SetClock request) {
        clock.set(request.instant());
        return state();
    }

    @DeleteMapping
    ClockState reset() {
        clock.reset();
        return state();
    }

    private ClockState state() {
        return new ClockState(clock.instant(), clock.isSet());
    }
}
