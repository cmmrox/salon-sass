package com.salonplatform.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * AC-1: every error answer carries the request's trace id, the same as on the request's log line,
 * and the trace id of a valid {@code traceparent} the caller sent.
 */
class ErrorTraceIntegrationTest extends IntegrationTest {

    private static final String TRACE_PARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    @ParameterizedTest
    @CsvSource({
        "GET, /api/v1/nothing-here, application/json, 404, not_found",
        "DELETE, /api/v1/meta, application/json, 405, method_not_allowed",
        "GET, /api/v1/meta, text/csv, 406, not_acceptable",
        "GET, /does-not-exist, application/json, 404, not_found",
    })
    void anErrorCarriesTheTraceIdOfTheCallersTraceParent(
            String method, String path, String accept, int status, String code) {
        Exchange response = http().sendOnHost(
                        method,
                        "localhost:" + port(),
                        path,
                        Map.of("Accept", accept, "traceparent", TRACE_PARENT),
                        null);

        assertThat(response.status()).isEqualTo(status);
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo(code);
        assertThat(JsonPath.parse(response.body()).<String>read("$.traceId")).isEqualTo(TRACE_ID);
    }

    @Test
    void anErrorsTraceIdIsTheTraceIdOfTheRequestsLogLine() {
        try (CapturedLogs logs = CapturedLogs.of("com.salonplatform.request")) {
            Exchange response =
                    http().sendOnHost("GET", "localhost:" + port(), "/api/v1/unknown-for-trace", Map.of(), null);

            String traceId = JsonPath.parse(response.body()).read("$.traceId");
            assertThat(traceId).matches("^[0-9a-f]{32}$");
            List<ILoggingEvent> lines =
                    logs.events(line -> "/api/v1/unknown-for-trace".equals(CapturedLogs.keyValue(line, "url.path")));
            assertThat(lines).hasSize(1);
            assertThat(CapturedLogs.context(lines.getFirst())).containsEntry("trace.id", traceId);
        }
    }

    /** Refused by the server before the application saw it: still a trace id, and still one log line. */
    @Test
    void aRequestTheServerRefusesItselfCarriesATraceIdAndWritesItsLine() {
        try (CapturedLogs logs = CapturedLogs.of("com.salonplatform.request")) {
            String raw = http().raw("GET /api/v1/%zz HTTP/1.1\r\nHost: localhost\r\ntraceparent: " + TRACE_PARENT
                    + "\r\nConnection: close\r\n\r\n");

            String body = raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1);
            assertThat(JsonPath.parse(body).<String>read("$.code")).isEqualTo("bad_request");
            assertThat(JsonPath.parse(body).<String>read("$.traceId")).isEqualTo(TRACE_ID);
            assertThat(logs.events(
                            line -> TRACE_ID.equals(CapturedLogs.context(line).get("trace.id"))))
                    .singleElement()
                    .satisfies(line -> assertThat(CapturedLogs.keyValue(line, "http.response.status_code"))
                            .isEqualTo(400));
        }
    }

    @Test
    void withoutATraceParentEachRequestHasATraceOfItsOwn() {
        String first = JsonPath.parse(http().get("/api/v1/x").body()).read("$.traceId");
        String second = JsonPath.parse(http().get("/api/v1/x").body()).read("$.traceId");

        assertThat(first).matches("^[0-9a-f]{32}$").isNotEqualTo(second);
    }
}
