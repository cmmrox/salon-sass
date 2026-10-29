package com.salonplatform.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Which hostnames can name a salon: docs/architecture/multi-tenancy.md, "Salons and their hostnames". */
class PlatformHostnameTest {

    private final PlatformHostname localhost = new PlatformHostname("localhost");

    @ParameterizedTest
    @CsvSource({
        "alpha.localhost,           alpha",
        "alpha.localhost:8090,      alpha",
        "ALPHA.LOCALHOST:8090,      alpha",
        "Alpha.LocalHost,           alpha",
        "' alpha.localhost:3100 ',  alpha",
        "x.localhost,               x",
        "console.localhost,         console",
    })
    void exactlyOneLabelInFrontOfThePlatformDomainIsACandidateSlug(String host, String slug) {
        assertThat(localhost.slugOf(host)).contains(slug);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "localhost:8090",
                "LOCALHOST",
                "alpha.beta.localhost",
                "alpha.beta.localhost:8090",
                "alpha.localhost.example.com",
                "alpha.localhost.example.com:8090",
                ".localhost",
                ".localhost:8090",
                "alpha.localhost.",
                "alphalocalhost",
                "127.0.0.1",
                "127.0.0.1:8090",
                "[::1]",
                "[::1]:8090",
                "example.com",
                "alpha.example.com",
            })
    void anyOtherHostnameNamesNoSalon(String host) {
        assertThat(localhost.slugOf(host)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void noHostNamesNoSalon(String host) {
        assertThat(localhost.slugOf(host)).isEmpty();
    }

    @Test
    void anotherPlatformDomainIsMatchedTheSameWay() {
        PlatformHostname staging = new PlatformHostname("Salons.Example.COM");

        assertThat(staging.slugOf("alpha.salons.example.com:443")).contains("alpha");
        assertThat(staging.slugOf("salons.example.com")).isEmpty();
        assertThat(staging.slugOf("alpha.localhost")).isEmpty();
        assertThat(staging.slugOf("a.b.salons.example.com")).isEmpty();
        assertThat(staging.slugOf("evilsalons.example.com")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".localhost", "localhost.", "localhost:8090"})
    void aPlatformDomainThatIsNotAHostnameIsRefused(String domain) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PlatformHostname(domain));
    }
}
