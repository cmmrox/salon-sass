package com.salonplatform;

import java.net.http.HttpResponse;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        for (String endpoint : new String[] { "env", "beans", "metrics", "configprops", "loggers", "heapdump" }) {
            assertThat(http().get("/actuator/" + endpoint).statusCode()).as(endpoint).isEqualTo(404);
        }
    }
}
