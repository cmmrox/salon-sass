package com.salonplatform.shared.infrastructure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Each request, including a denial before the logging filter, writes one sanitized event. */
class RequestLogIntegrationTest extends IntegrationTest {

    @ParameterizedTest
    @CsvSource({
        "active, true, true, 401, invalid_session",
        "active, true, false, 401, invalid_session",
        "native, true, true, 401, invalid_session",
        "native, true, false, 401, invalid_session",
        "unknown, true, true, 401, invalid_session",
        "suspended, true, true, 401, invalid_session",
        "unknown, false, true, 404, not_found",
        "suspended, false, true, 403, salon_suspended",
        "active, false, true, 200, unused",
        "active, false, false, 200, unused",
    })
    void requestsAndEarlyDenialsWriteExactlyOneSafeEvent(String address, boolean invalidBearer,
            boolean continueTrace, int status, String code) {
        TestSalons salons = new TestSalons(TestDatabase.of(TestPostgres.shared()).asOwner());
        var salon = salons.create("Request logging studio", "#D6086A");
        if (address.equals("suspended")) {
            salons.suspend(salon);
        }
        String host = switch (address) {
            case "native" -> "localhost:" + port();
            case "unknown" -> "nosuchsalon.localhost:" + port();
            default -> salon.host(port());
        };
        String callerTrace = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Cookie", "session=log-cookie-marker");
        if (invalidBearer) {
            headers.put("Authorization", "Bearer log-credential-marker");
        }
        if (continueTrace) {
            headers.put("traceparent", "00-" + callerTrace + "-b7ad6b7169203331-01");
        }

        try (CapturedLogs logs = CapturedLogs.ofApplication()) {
            var response = http().sendOnHost("GET", host, "/api/v1/salon?secret=log-query-marker",
                    headers, "{\"note\":\"log-body-marker\"}");

            assertThat(response.status()).isEqualTo(status);
            String responseTrace = status == 200 ? null : JsonPath.parse(response.body()).read("$.traceId");
            if (status != 200) {
                assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo(code);
                assertThat(responseTrace).matches("[0-9a-f]{32}");
                if (continueTrace) {
                    assertThat(responseTrace).isEqualTo(callerTrace);
                }
            }
            var events = logs.events(line -> "com.salonplatform.request".equals(line.getLoggerName()));
            assertThat(events).hasSize(1);
            var event = events.getFirst();
            assertThat(CapturedLogs.keyValue(event, "http.request.method")).isEqualTo("GET");
            assertThat(CapturedLogs.keyValue(event, "url.path")).isEqualTo("/api/v1/salon");
            assertThat(CapturedLogs.keyValue(event, "http.response.status_code")).isEqualTo(status);
            assertThat((Long) CapturedLogs.keyValue(event, "event.duration")).isPositive();
            assertThat(CapturedLogs.context(event).get("trace.id")).matches("[0-9a-f]{32}");
            if (continueTrace || status != 200) {
                assertThat(CapturedLogs.context(event).get("trace.id"))
                        .isEqualTo(continueTrace ? callerTrace : responseTrace);
            }
            if (status == 200) {
                assertThat(CapturedLogs.context(event)).containsEntry("tenant.id", salon.id().toString());
            }
            else {
                assertThat(CapturedLogs.context(event)).doesNotContainKey("tenant.id");
            }
            for (var line : logs.events()) {
                String everything = line.getFormattedMessage() + line.getKeyValuePairs() + line.getMDCPropertyMap();
                assertThat(everything).doesNotContain("log-query-marker", "log-cookie-marker",
                        "log-credential-marker", "log-body-marker", "secret=");
            }
        }
    }
}
