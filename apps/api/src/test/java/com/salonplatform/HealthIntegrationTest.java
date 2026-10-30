package com.salonplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class HealthIntegrationTest extends IntegrationTest {

    @Test
    void healthIsUpAndSaysTheDatabaseIsUp() {
        HttpResponse<String> response = http().get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<String>read("$.status")).isEqualTo("UP");
        assertThat(body.<String>read("$.components.db.status")).isEqualTo("UP");
    }

    @Test
    void healthGivesNoDetailBeyondEachComponentsStatus() {
        HttpResponse<String> response = http().get("/actuator/health");

        assertThat(response.body()).doesNotContain("details").doesNotContainIgnoringCase("postgres");
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() {
        for (String endpoint : new String[] {"env", "beans", "metrics", "configprops", "loggers", "heapdump"}) {
            assertThat(http().get("/actuator/" + endpoint).statusCode())
                    .as(endpoint)
                    .isEqualTo(404);
        }
    }
}
