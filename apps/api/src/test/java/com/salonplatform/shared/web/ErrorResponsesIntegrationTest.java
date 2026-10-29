package com.salonplatform.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ErrorResponsesIntegrationTest extends IntegrationTest {

    @ParameterizedTest
    @ValueSource(
            strings = {"/api/v1/does-not-exist", "/api/v2/meta", "/api", "/does-not-exist", "/actuator/does-not-exist"})
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

    /** Requests the server refuses before the application sees them. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/%zz HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
                "GET /api/v1/meta HTTP/1.1\r\nHost: localhost\r\nContent-Length: abc\r\nConnection: close\r\n\r\n",
                "GET /api/v1/meta HTTP/1.1\r\nConnection: close\r\n\r\n",
            })
    void aMalformedRequestIsRefusedAsProblemDetails(String request) {
        String response = http().raw(request);

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).containsIgnoringCase("Content-Type: application/problem+json");
        String body = response.substring(response.indexOf("{"), response.lastIndexOf("}") + 1);
        assertThat(JsonPath.parse(body).<String>read("$.code")).isEqualTo("bad_request");
        assertThat(JsonPath.parse(body).<Integer>read("$.status")).isEqualTo(400);
        assertThat(response).doesNotContain("<html").doesNotContainIgnoringCase("Server:");
        assertLeaksNothing(body);
    }

    /** No stack trace, class name or framework name; the only trace is the request's trace id. */
    private static void assertLeaksNothing(String body) {
        assertThat(JsonPath.parse(body).<String>read("$.traceId")).matches("^[0-9a-f]{32}$");
        assertThat(body.replace("\"traceId\"", ""))
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("springframework")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContainIgnoringCase("salonplatform")
                .doesNotContain("java.")
                .doesNotContain("\tat ")
                .doesNotContainIgnoringCase("trace");
    }
}
