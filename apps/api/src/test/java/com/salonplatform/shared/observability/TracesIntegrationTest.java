package com.salonplatform.shared.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The spans the API records (NFR-16), read as they end: one for each request, each delivery and
 * each job run for a salon, carrying the salon's id as {@code tenant.id}; a caller's W3C
 * {@code traceparent} is continued; a message's delivery belongs to the trace of the request that
 * wrote it. Nothing is exported here: no OTLP endpoint is configured.
 */
class TracesIntegrationTest {

    private static final AttributeKey<String> TENANT_ID = AttributeKey.stringKey("tenant.id");

    private static final String ALPHA = "a1a1a1a1-0000-4000-8000-000000000001";

    private static final String BETA = "b2b2b2b2-0000-4000-8000-000000000002";

    private static final String GAMMA = "c3c3c3c3-0000-4000-8000-000000000003";

    private static final List<SpanData> SPANS = new CopyOnWriteArrayList<>();

    private static StartedApplication application;

    /** Added to the application under test: keeps every span that ends. */
    @Configuration(proxyBeanMethods = false)
    static class RecordedSpans {

        @Bean
        SpanProcessor recordingSpanProcessor() {
            return new SpanProcessor() {
                @Override
                public void onStart(Context parentContext, ReadWriteSpan span) {
                }

                @Override
                public boolean isStartRequired() {
                    return false;
                }

                @Override
                public void onEnd(ReadableSpan span) {
                    SPANS.add(span.toSpanData());
                }

                @Override
                public boolean isEndRequired() {
                    return true;
                }
            };
        }
    }

    @BeforeAll
    static void start() {
        application = TestDatabase.createEmpty(TestPostgres.shared()).startWith(RecordedSpans.class,
                "--spring.profiles.active=local", "--salon.outbox.enabled=true", "--salon.outbox.poll-interval=100ms",
                "--salon.jobs.enabled=true", "--salon.jobs.poll-interval=100ms");
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void aRequestOnASalonsHostnameContinuesTheCallersTraceAndCarriesTheSalon() {
        String traceId = "11111111111111111111111111111111";
        Exchange response = application.http().sendOnHost("GET", "alpha.localhost:" + application.port(), "/api/v1/salon",
                Map.of("traceparent", "00-" + traceId + "-aaaaaaaaaaaaaaaa-01"), null);
        assertThat(response.status()).isEqualTo(200);

        SpanData request = await(span -> span.getTraceId().equals(traceId) && span.getParentSpanId().equals("aaaaaaaaaaaaaaaa"));
        assertThat(request.getAttributes().get(TENANT_ID)).isEqualTo(ALPHA);
        assertThat(request.getName()).containsIgnoringCase("get");
        assertThat(SPANS).filteredOn(span -> span.getTraceId().equals(traceId))
                .as("every span of the request is in the caller's trace").isNotEmpty();
    }

    @Test
    void aRequestOnNoSalonsHostnameHasASpanWithNoSalon() {
        String traceId = "22222222222222222222222222222222";
        application.http().sendOnHost("GET", "localhost:" + application.port(), "/api/v1/meta",
                Map.of("traceparent", "00-" + traceId + "-bbbbbbbbbbbbbbbb-01"), null);
        application.http().sendOnHost("GET", "nosuchsalon.localhost:" + application.port(), "/api/v1/salon",
                Map.of("traceparent", "00-" + traceId + "-cccccccccccccccc-01"), null);

        SpanData meta = await(span -> span.getTraceId().equals(traceId) && span.getParentSpanId().equals("bbbbbbbbbbbbbbbb"));
        SpanData noSalon = await(span -> span.getTraceId().equals(traceId) && span.getParentSpanId().equals("cccccccccccccccc"));
        assertThat(meta.getAttributes().get(TENANT_ID)).isNull();
        assertThat(noSalon.getAttributes().get(TENANT_ID)).isNull();
    }

    @Test
    void aMessagesDeliveryBelongsToTheTraceOfTheRequestThatWroteItAndCarriesItsSalon() {
        String traceId = "33333333333333333333333333333333";
        Exchange created = application.http().sendOnHost("POST", "beta.localhost:" + application.port(), "/local/probes",
                Map.of("Content-Type", "application/json", "traceparent", "00-" + traceId + "-dddddddddddddddd-01"), "{}");
        assertThat(created.status()).isEqualTo(201);
        assertThat(JsonPath.parse(created.body()).<String>read("$.audit[0].traceId")).isEqualTo(traceId);

        SpanData delivery = await(span -> span.getName().equals("outbox.delivery") && span.getTraceId().equals(traceId));
        assertThat(delivery.getAttributes().get(TENANT_ID)).isEqualTo(BETA);
        SpanData request = await(span -> span.getTraceId().equals(traceId) && span.getParentSpanId().equals("dddddddddddddddd"));
        assertThat(request.getAttributes().get(TENANT_ID)).isEqualTo(BETA);
        assertThat(SPANS).filteredOn(span -> span.getTraceId().equals(traceId)).extracting(SpanData::getSpanId)
                .as("the delivery's parent is a span of the request that wrote the message")
                .contains(delivery.getParentSpanId());
    }

    @Test
    void aJobsRunForEachSalonIsASpanOfItsOwnTraceCarryingThatSalon() {
        for (String salon : new String[] { ALPHA, BETA, GAMMA }) {
            SpanData run = await(span -> span.getName().equals("job.outbox-cleanup")
                    && salon.equals(span.getAttributes().get(TENANT_ID)));
            assertThat(run.getParentSpanContext().isValid()).as("a job's run starts a trace of its own").isFalse();
        }
        assertThat(SPANS).filteredOn(span -> span.getName().equals("job.outbox-cleanup")).extracting(SpanData::getTraceId)
                .as("one trace for each salon's run").doesNotHaveDuplicates();
    }

    private static SpanData await(Predicate<SpanData> matching) {
        Instant until = Instant.now().plus(Duration.ofSeconds(5));
        while (true) {
            for (SpanData span : SPANS) {
                if (matching.test(span)) {
                    return span;
                }
            }
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("no such span within 5 seconds; recorded: "
                        + SPANS.stream().map(span -> span.getName() + " " + span.getAttributes()).toList());
            }
            try {
                Thread.sleep(25);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }
}
