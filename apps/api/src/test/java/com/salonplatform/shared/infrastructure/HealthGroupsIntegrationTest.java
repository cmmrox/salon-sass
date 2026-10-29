package com.salonplatform.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The readiness and liveness groups (F01-S05): what the edge sends traffic by, and what says the
 * instance is alive. Each shows its status and nothing else.
 */
class HealthGroupsIntegrationTest extends IntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void readinessAndLivenessAreUpAndShowTheirStatusOnly() {
        for (String group : new String[] {"readiness", "liveness"}) {
            HttpResponse<String> response = http().get("/actuator/health/" + group);

            assertThat(response.statusCode()).as(group).isEqualTo(200);
            DocumentContext body = JsonPath.parse(response.body());
            assertThat(body.<String>read("$.status")).as(group).isEqualTo("UP");
            assertThat(body.<java.util.Map<String, Object>>read("$")).as(group).containsOnlyKeys("status");
        }
    }

    @Test
    void readinessGoesOutOfServiceWhenTheInstanceRefusesTrafficWhileLivenessStaysUp() {
        AvailabilityChangeEvent.publish(events, this, ReadinessState.REFUSING_TRAFFIC);
        try {
            HttpResponse<String> readiness = http().get("/actuator/health/readiness");
            HttpResponse<String> liveness = http().get("/actuator/health/liveness");

            assertThat(readiness.statusCode()).isEqualTo(503);
            assertThat(JsonPath.parse(readiness.body()).<String>read("$.status"))
                    .isEqualTo("OUT_OF_SERVICE");
            assertThat(liveness.statusCode()).as(liveness.body()).isEqualTo(200);
            assertThat(JsonPath.parse(liveness.body()).<String>read("$.status")).isEqualTo("UP");
            // The instance still answers everything else while it is out of the rotation.
            assertThat(http().get("/api/v1/meta").statusCode()).isEqualTo(200);
        } finally {
            AvailabilityChangeEvent.publish(events, this, ReadinessState.ACCEPTING_TRAFFIC);
        }
        assertThat(http().get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    @Test
    void theFullHealthStillReportsTheDatabase() {
        DocumentContext body = JsonPath.parse(http().get("/actuator/health").body());

        assertThat(body.<String>read("$.components.db.status")).isEqualTo("UP");
    }
}
