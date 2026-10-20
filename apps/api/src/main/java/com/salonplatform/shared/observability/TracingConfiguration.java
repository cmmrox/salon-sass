package com.salonplatform.shared.observability;

import io.micrometer.tracing.otel.bridge.Slf4JEventListener;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The names of the trace fields on every log line: {@code trace.id} and {@code span.id}, the
 * Elastic Common Schema's names, beside {@code tenant.id} (NFR-16). Spring Boot's own listener,
 * which this replaces, would name them {@code traceId} and {@code spanId}.
 */
@Configuration(proxyBeanMethods = false)
class TracingConfiguration {

    static final String TRACE_ID = "trace.id";

    static final String SPAN_ID = "span.id";

    /**
     * A request's URL as a span records it, with an export download's token replaced (F03-S03), so no
     * download credential leaves the process in a trace.
     */
    @Bean
    io.micrometer.observation.ObservationFilter exportTokenRedaction() {
        return context -> {
            for (String name : new String[] {"http.url", "url.path", "uri"}) {
                io.micrometer.common.KeyValue value = context.getHighCardinalityKeyValue(name);
                if (value != null && value.getValue() != null && value.getValue().contains("/api/v1/exports/")) {
                    context.addHighCardinalityKeyValue(io.micrometer.common.KeyValue.of(name,
                            com.salonplatform.shared.api.RequestPaths.loggable(value.getValue())));
                }
            }
            return context;
        };
    }

    @Bean
    Slf4JEventListener otelSlf4JEventListener() {
        return new Slf4JEventListener(TRACE_ID, SPAN_ID);
    }
}
