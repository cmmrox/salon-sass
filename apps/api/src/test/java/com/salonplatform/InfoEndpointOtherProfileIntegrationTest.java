package com.salonplatform;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** The base class runs with the {@code test} profile only: any profile that is not {@code local}. */
class InfoEndpointOtherProfileIntegrationTest extends IntegrationTest {

    @Test
    void infoIsNotFoundOutsideTheLocalProfile() {
        HttpResponse<String> response = http().get("/actuator/info");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Http.contentType(response)).startsWith("application/problem+json");
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("not_found");
    }
}
