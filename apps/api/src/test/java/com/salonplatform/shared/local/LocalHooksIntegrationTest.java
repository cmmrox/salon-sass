package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.ProblemBodies;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local test hooks on the synthetic salons, with the dispatcher off (the test profile), so that
 * a probe stays as it was written: the test clock, the probe and what it writes, the error format
 * on the hooks, and the request's log line.
 */
@ActiveProfiles({ "test", "local" })
class LocalHooksIntegrationTest extends IntegrationTest {

    private static final String ALPHA = "a1a1a1a1-0000-4000-8000-000000000001";

    private static final String BETA = "b2b2b2b2-0000-4000-8000-000000000002";

    private static final String GAMMA = "c3c3c3c3-0000-4000-8000-000000000003";

    private static final String TRACE_PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();

    @AfterEach
    void realTime() {
        http().sendOnHost("DELETE", "localhost:" + port(), "/local/clock", Map.of(), null);
    }

    private String host(String slug) {
        return slug + ".localhost:" + port();
    }

    @Test
    void theClockCanBeReadSetAndReturnedToRealTime() {
        DocumentContext real = JsonPath.parse(http().sendOnHost("GET", "localhost:" + port(), "/local/clock", Map.of(),
                null).body());
        assertThat(real.<Boolean>read("$.fixed")).isFalse();
        assertThat(Duration.between(Instant.parse(real.read("$.now")), Instant.now()).abs()).isLessThan(Duration.ofSeconds(5));

        Exchange set = http().sendOnHost("PUT", "localhost:" + port(), "/local/clock",
                Map.of("Content-Type", "application/json"), "{\"instant\":\"2030-01-01T00:00:00Z\"}");
        assertThat(set.status()).isEqualTo(200);
        assertThat(JsonPath.parse(set.body()).<Boolean>read("$.fixed")).isTrue();
        Instant meta = Instant.parse(JsonPath.parse(http().get("/api/v1/meta").body()).read("$.time"));
        assertThat(Duration.between(Instant.parse("2030-01-01T00:00:00Z"), meta)).isBetween(Duration.ZERO,
                Duration.ofSeconds(5));

        Exchange reset = http().sendOnHost("DELETE", "localhost:" + port(), "/local/clock", Map.of(), null);
        assertThat(JsonPath.parse(reset.body()).<Boolean>read("$.fixed")).isFalse();
        assertThat(Instant.parse(JsonPath.parse(reset.body()).read("$.now"))).isBefore(Instant.parse("2030-01-01T00:00:00Z"));
    }

    @Test
    void settingTheClockWithoutAnInstantIsABadRequestThatNamesTheField() {
        Exchange response = http().sendOnHost("PUT", "localhost:" + port(), "/local/clock",
                Map.of("Content-Type", "application/json"), "{}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(JsonPath.parse(response.body()).<List<String>>read("$.errors[*].field")).containsExactly("instant");
    }

    @Test
    void aProbeWritesOneMessageAndOneAuditEntryForItsSalonWithTheRequestsTrace() {
        Exchange created = http().sendOnHost("POST", host("alpha"), "/local/probes",
                Map.of("Content-Type", "application/json", "traceparent", TRACE_PARENT), "{\"failures\":1}");

        assertThat(created.status()).isEqualTo(201);
        DocumentContext probe = JsonPath.parse(created.body());
        String id = probe.read("$.id");
        assertThat(created.headers()).containsEntry("location", "/local/probes/" + id);
        assertThat(probe.<String>read("$.status")).isEqualTo("pending");
        assertThat(probe.<Integer>read("$.attempts")).isZero();
        assertThat(probe.<Integer>read("$.providerCalls")).isZero();
        assertThat(probe.<List<Object>>read("$.audit")).hasSize(1);
        assertThat(probe.<String>read("$.audit[0].action")).isEqualTo("probe.created");
        assertThat(probe.<String>read("$.audit[0].actorKind")).isEqualTo("system");
        assertThat(probe.<String>read("$.audit[0].occurredAt")).isEqualTo(probe.read("$.createdAt"));
        assertThat(probe.<Map<String, Object>>read("$.audit[0].after")).containsEntry("failures", 1)
                .containsEntry("delayMs", 0).containsEntry("abort", false);
        assertThat(probe.<String>read("$.audit[0].traceId")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

        Map<String, Object> message = owner.queryForMap(
                "SELECT tenant_id::text AS tenant, channel, status, trace_parent FROM outbox_message WHERE id = ?::uuid", id);
        assertThat(message).containsEntry("tenant", ALPHA).containsEntry("channel", "probe").containsEntry("status", "pending");
        assertThat((String) message.get("trace_parent")).startsWith("00-4bf92f3577b34da6a3ce929d0e0e4736-");
        assertThat(owner.queryForObject("SELECT tenant_id::text FROM audit_entry WHERE subject_id = ?", String.class, id))
                .isEqualTo(ALPHA);
    }

    @Test
    void aProbeThatAbortsAnswersInternalErrorAndLeavesNothingBehind() {
        int messages = count("outbox_message");
        int entries = count("audit_entry");

        try (CapturedLogs logs = CapturedLogs.ofApplication()) {
            Exchange response = http().sendOnHost("POST", host("beta"), "/local/probes",
                    Map.of("Content-Type", "application/json", "traceparent", TRACE_PARENT), "{\"abort\":true}");

            assertThat(response.status()).isEqualTo(500);
            assertThat(response.contentType()).startsWith("application/problem+json");
            assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("internal_error");
            assertThat(JsonPath.parse(response.body()).<String>read("$.traceId")).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(response.body()).doesNotContain("ProbeAborted").doesNotContain("abort").doesNotContain("Exception");
            assertThat(count("outbox_message")).isEqualTo(messages);
            assertThat(count("audit_entry")).isEqualTo(entries);

            // What went wrong is in the log, under the same trace and the salon, and not in the answer.
            assertThat(logs.events(line -> "4bf92f3577b34da6a3ce929d0e0e4736".equals(CapturedLogs.context(line).get("trace.id"))))
                    .hasSize(2)
                    .allSatisfy(line -> assertThat(CapturedLogs.context(line)).containsEntry("tenant.id", BETA))
                    .anySatisfy(line -> assertThat(line.getFormattedMessage()).isEqualTo("Unhandled exception"))
                    .anySatisfy(line -> assertThat(CapturedLogs.keyValue(line, "http.response.status_code")).isEqualTo(500));
        }
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"failures\":-1}                          | failures",
        "{\"failures\":51}                          | failures",
        "{\"failures\":\"abc\"}                     | failures",
        // A value of another JSON type is not turned into the declared one: a number in quotes is
        // not a number, a decimal is not a whole number, and 1 is not true.
        "{\"failures\":\"5\"}                       | failures",
        "{\"failures\":1.5}                       | failures",
        "{\"delayMs\":\"10\"}                       | delayMs",
        "{\"abort\":1}                            | abort",
        "{\"abort\":\"true\"}                       | abort",
        "{\"delayMs\":-1}                           | delayMs",
        "{\"delayMs\":60001}                        | delayMs",
        "{\"failures\":99,\"delayMs\":-5}           | delayMs,failures",
    })
    void aProbeWhoseBodyFailsValidationIsABadRequestNamingEachFieldAndWritesNothing(String body, String fields) {
        int messages = count("outbox_message");
        int entries = count("audit_entry");

        Exchange response = http().postJsonOnHost(host("alpha"), "/local/probes", body);

        assertThat(response.status()).as(body).isEqualTo(400);
        assertThat(response.contentType()).startsWith("application/problem+json");
        DocumentContext problem = JsonPath.parse(response.body());
        assertThat(problem.<String>read("$.code")).isEqualTo("bad_request");
        assertThat(problem.<String>read("$.traceId")).matches("^[0-9a-f]{32}$");
        assertThat(problem.<List<String>>read("$.errors[*].field")).containsExactly(fields.split(","));
        assertThat(problem.<List<String>>read("$.errors[*].detail")).allMatch(detail -> !detail.isBlank());
        assertThat(ProblemBodies.withoutTraceId(response.body())).as("not the value it refused").doesNotContain("abc").doesNotContain("60001")
                .doesNotContain("-5").doesNotContain("99").doesNotContain("Exception").doesNotContain("java");
        assertThat(count("outbox_message")).isEqualTo(messages);
        assertThat(count("audit_entry")).isEqualTo(entries);
    }

    @Test
    void aProbeBodyThatIsNotJsonIsABadRequestWithNoField() {
        Exchange response = http().postJsonOnHost(host("alpha"), "/local/probes", "this is not json");

        assertThat(response.status()).isEqualTo(400);
        DocumentContext problem = JsonPath.parse(response.body());
        assertThat(problem.<String>read("$.code")).isEqualTo("bad_request");
        assertThat(problem.<List<Object>>read("$.errors")).isEmpty();
        assertThat(response.body()).doesNotContain("this is not json");
    }

    @Test
    void aProbeInAnotherFormatIsUnsupportedMediaType() {
        Exchange response = http().sendOnHost("POST", host("alpha"), "/local/probes",
                Map.of("Content-Type", "text/plain"), "failures=1");

        assertThat(response.status()).isEqualTo(415);
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("unsupported_media_type");
        assertThat(JsonPath.parse(response.body()).<String>read("$.traceId")).matches("^[0-9a-f]{32}$");
    }

    @Test
    void anotherSalonsProbeIsNotFoundWithTheSameBodyAsOneThatDoesNotExist() {
        String id = JsonPath.parse(http().postJsonOnHost(host("alpha"), "/local/probes", "{}").body()).read("$.id");

        Exchange onBeta = http().getOnHost(host("beta"), "/local/probes/" + id);
        Exchange missing = http().getOnHost(host("beta"), "/local/probes/00000000-0000-4000-8000-00000000abcd");
        Exchange onAlpha = http().getOnHost(host("alpha"), "/local/probes/" + id);

        assertThat(onBeta.status()).isEqualTo(404);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(JsonPath.parse(onBeta.body()).<String>read("$.code")).isEqualTo("not_found");
        assertThat(ProblemBodies.withoutTraceId(onBeta.body()).replace(id, "{id}"))
                .isEqualTo(ProblemBodies.withoutTraceId(missing.body()).replace("00000000-0000-4000-8000-00000000abcd", "{id}"));
        assertThat(onBeta.body()).doesNotContain(ALPHA);
        assertThat(onAlpha.status()).isEqualTo(200);
        assertThat(http().getOnHost("nosuchsalon.localhost:" + port(), "/local/probes/" + id).status()).isEqualTo(404);
        assertThat(http().getOnHost(host("alpha"), "/local/probes/not-a-uuid").status()).isEqualTo(404);
    }

    /** AC-5: London on both sides of the change of clocks, and Colombo across midnight. */
    @ParameterizedTest
    @CsvSource({
        "gamma, 2026-03-29T00:30:00Z, 2026-03-29T00:30:00+00:00",
        "gamma, 2026-03-29T01:30:00Z, 2026-03-29T02:30:00+01:00",
        "alpha, 2026-10-12T20:00:00Z, 2026-10-13T01:30:00+05:30",
    })
    void aProbeShowsWhenItWasCreatedInItsSalonsTime(String salon, String clock, String local) {
        http().sendOnHost("PUT", "localhost:" + port(), "/local/clock", Map.of("Content-Type", "application/json"),
                "{\"instant\":\"" + clock + "\"}");

        DocumentContext probe = JsonPath.parse(http().postJsonOnHost(host(salon), "/local/probes", "{}").body());

        assertThat(probe.<String>read("$.createdAt")).isEqualTo(clock);
        assertThat(probe.<String>read("$.createdAtLocal")).isEqualTo(local);
        assertThat(probe.<String>read("$.audit[0].occurredAt")).isEqualTo(clock);
        Instant stored = owner.queryForObject("SELECT created_at FROM outbox_message WHERE id = ?::uuid",
                (row, n) -> row.getObject(1, java.time.OffsetDateTime.class).toInstant(), probe.<String>read("$.id"));
        assertThat(Duration.between(Instant.parse(clock), stored)).isBetween(Duration.ZERO, Duration.ofSeconds(1));
    }

    /** AC-14 and AC-1: the request's one line, its salon, its trace, and nothing of its body or query. */
    @Test
    void eachRequestWritesOneLineWithItsSalonAndTraceAndNothingItCarried() {
        try (CapturedLogs logs = CapturedLogs.of("com.salonplatform.request")) {
            Exchange response = http().sendOnHost("POST", host("gamma"), "/local/probes?secret=query-value",
                    Map.of("Content-Type", "application/json", "Cookie", "session=cookie-value",
                            "Authorization", "Bearer auth-value", "traceparent", TRACE_PARENT),
                    "{\"failures\":-1,\"note\":\"body-value\"}");
            Exchange noSalon = http().getOnHost("nosuchsalon.localhost:" + port(), "/api/v1/salon");

            assertThat(response.status()).isEqualTo(400);
            List<ILoggingEvent> lines = logs.events();
            ILoggingEvent probe = lines.stream().filter(line -> "/local/probes".equals(CapturedLogs.keyValue(line, "url.path")))
                    .findFirst().orElseThrow();
            assertThat(CapturedLogs.keyValue(probe, "http.request.method")).isEqualTo("POST");
            assertThat(CapturedLogs.keyValue(probe, "http.response.status_code")).isEqualTo(400);
            assertThat((Long) CapturedLogs.keyValue(probe, "event.duration")).isPositive();
            assertThat(CapturedLogs.context(probe)).containsEntry("tenant.id", GAMMA)
                    .containsEntry("trace.id", "4bf92f3577b34da6a3ce929d0e0e4736");
            assertThat(JsonPath.parse(response.body()).<String>read("$.traceId"))
                    .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");

            ILoggingEvent missing = lines.stream().filter(line -> "/api/v1/salon".equals(CapturedLogs.keyValue(line, "url.path")))
                    .findFirst().orElseThrow();
            assertThat(CapturedLogs.keyValue(missing, "http.response.status_code")).isEqualTo(404);
            assertThat(CapturedLogs.context(missing)).doesNotContainKey("tenant.id");
            assertThat(CapturedLogs.context(missing).get("trace.id"))
                    .isEqualTo(JsonPath.parse(noSalon.body()).read("$.traceId"));

            for (ILoggingEvent line : lines) {
                String everything = line.getFormattedMessage() + line.getKeyValuePairs() + line.getMDCPropertyMap();
                assertThat(everything).doesNotContain("query-value", "cookie-value", "auth-value", "body-value", "secret=");
            }
        }
    }

    /** The hooks are served here, in the local profile, and still are no part of the contract. */
    @Test
    void theOpenApiDocumentDescribesNoneOfTheHooksEvenWhereTheyAreServed() {
        assertThat(http().sendOnHost("GET", "localhost:" + port(), "/local/clock", Map.of(), null).status()).isEqualTo(200);

        String document = http().get("/v3/api-docs").body();

        assertThat(JsonPath.parse(document).<Map<String, Object>>read("$.paths").keySet())
                .isNotEmpty().allMatch(path -> path.startsWith("/api/v1/"));
        assertThat(document).doesNotContain("/local/").doesNotContain("actuator").doesNotContainIgnoringCase("probe")
                .doesNotContainIgnoringCase("clock");
    }

    private int count(String table) {
        return owner.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
