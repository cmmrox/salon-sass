package com.salonplatform.platform;

import java.util.Map;

import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security review fix: a request whose raw path is not canonical is refused 400 before any path
 * decision, so no spelling reaches an operator handler past the console-host check, or a local hook
 * past its prefix checks. Every variant here would dispatch to a real handler if it were let through.
 */
class CanonicalPathIntegrationTest extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/operator;x=1/me", "/api/v1/operator/me;x=1", "/api/v1/%6Fperator/me",
        "/api/v1/op%65rator/plans", "//api/v1/operator/me", "/api//v1/operator/me", "/api/v1/./operator/me",
        "/api/v1/x/../operator/me", "/api/v1/operator/x/../me", "/local/operator;x/fixtures",
        "/local/%6Fperator/bootstrap-failure", "//local/operator/bootstrap-failure", "/local/./operator/fixtures",
        "/api/v1/identity;x/me", "/api/v1/%69dentity/staff/sign-in"})
    void aNonCanonicalPathIsRefusedOnEveryHostBeforeAnyHandler(String path) {
        for (String host : new String[] {"localhost:" + port(), "alpha.localhost:" + port(),
            OperatorTestSupport.CONSOLE + ":" + port()}) {
            Http.Exchange answer = http().sendOnHost("GET", host, path, Map.of(), null);
            assertThat(answer.status()).as("%s on %s: %s", path, host, answer.body()).isIn(400, 404);
            assertThat(answer.body()).doesNotContain("\"kind\"", "\"permissions\"", "\"step\"");
        }
    }
}
