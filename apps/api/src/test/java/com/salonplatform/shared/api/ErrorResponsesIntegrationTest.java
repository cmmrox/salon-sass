package com.salonplatform.shared.api;

import java.net.http.HttpResponse;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponsesIntegrationTest extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = { "/api/v1/does-not-exist", "/api/v2/meta", "/api", "/does-not-exist", "/actuator/does-not-exist" })
    void anUnknownPathIsNotFoundAsProblemDetails(String path) {
        HttpResponse<String> response = http().get(path);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Http.contentType(response)).startsWith("application/problem+json");
        DocumentContext body = JsonPath.parse(response.body());
        assertThat(body.<String>read("$.code")).isEqualTo("not_found");
        assertThat(body.<Integer>read("$.status")).isEqualTo(404);
        assertThat(body.<String>read("$.title")).isEqualTo("Not Found");
        assertThat(body.<String>read("$.instance")).isEqualTo(path);
        assertLeaksNothing(response.body());
    }

    @Test
    void theWrongMethodIsRefusedAsProblemDetails() {
        HttpResponse<String> response = http().send("POST", "/api/v1/meta");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(Http.contentType(response)).startsWith("application/problem+json");
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("method_not_allowed");
        assertLeaksNothing(response.body());
    }

    @Test
    void theErrorPathItselfAnswersAsProblemDetails() {
        HttpResponse<String> response = http().get("/error");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Http.contentType(response)).startsWith("application/problem+json");
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("not_found");
        assertLeaksNothing(response.body());
    }

    private static void assertLeaksNothing(String body) {
        assertThat(body)
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("springframework")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContainIgnoringCase("salonplatform")
                .doesNotContain("java.")
                .doesNotContain("\tat ")
                .doesNotContain("trace");
    }
}
