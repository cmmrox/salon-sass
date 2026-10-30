package com.salonplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"test", "local"})
class InfoEndpointLocalProfileIntegrationTest extends IntegrationTest {

    @Test
    void infoReportsJava25AndThatRequestsRunOnVirtualThreads() {
        HttpResponse<String> response = http().get("/actuator/info");

        assertThat(response.statusCode()).isEqualTo(200);
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<Integer>read("$.runtime.javaMajorVersion")).isEqualTo(25);
        assertThat(body.<Boolean>read("$.runtime.virtualThreads")).isTrue();
        assertThat(body.<String>read("$.java.version")).startsWith("25");
    }
}
