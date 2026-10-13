package com.salonplatform.shared.local;

import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-16: outside the local profile the hooks and the meters do not exist. The base class runs the test profile only. */
class NotLocalIntegrationTest extends IntegrationTest {

    @ParameterizedTest
    @CsvSource({
        "GET, localhost, /local/clock",
        "PUT, localhost, /local/clock",
        "DELETE, localhost, /local/clock",
        "POST, alpha.localhost, /local/probes",
        "GET, alpha.localhost, /local/probes/00000000-0000-4000-8000-000000000001",
        "GET, localhost, /local/anything",
        "GET, localhost, /actuator/metrics",
        "GET, localhost, /actuator/metrics/salon.outbox.dead",
    })
    void theHooksAndTheMetersAreNotFound(String method, String host, String path) {
        Exchange response = http().sendOnHost(method, host + ":" + port(), path,
                Map.of("Content-Type", "application/json"), "PUT".equals(method) || "POST".equals(method) ? "{}" : null);

        assertThat(response.status()).as("%s %s", method, path).isEqualTo(404);
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("not_found");
    }
}
