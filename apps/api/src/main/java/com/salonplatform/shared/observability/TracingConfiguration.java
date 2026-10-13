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

    @Bean
    Slf4JEventListener otelSlf4JEventListener() {
        return new Slf4JEventListener(TRACE_ID, SPAN_ID);
    }
}
