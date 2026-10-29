package com.salonplatform.shared.observability;

import com.salonplatform.shared.api.RequestTrace;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;

/**
 * The traces of work that is not a request: what the outbox stores so that a message's delivery
 * joins the trace of the request that wrote it, and the spans of a delivery and of a job's run for
 * one salon, each carrying the salon's id as {@code tenant.id} (NFR-16).
 */
@Component
public class Traces {

    private final Tracer tracer;

    private final Propagator propagator;

    public Traces(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The W3C {@code traceparent} of the current span, when there is one. */
    public Optional<String> currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return Optional.empty();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return Optional.ofNullable(carrier.get(RequestTrace.TRACE_PARENT))
                .filter(header -> RequestTrace.fromTraceParent(header).isPresent());
    }

    /** The trace id of the current span, when there is one. */
    public Optional<String> currentTraceId() {
        Span span = tracer.currentSpan();
        return Optional.ofNullable(span)
                .map(Span::context)
                .map(TraceContext::traceId)
                .filter(RequestTrace::isTraceId);
    }

    /**
     * Runs work in a new span for one salon: a child of the trace whose {@code traceparent} is given,
     * or the root of a new trace when none is. The span carries {@code tenant.id}, and while the work
     * runs it is the current span, so every log line written carries its {@code trace.id} and
     * {@code span.id}. A failure is recorded on the span and thrown on.
     */
    public <R> R inSpan(String name, CurrentTenant salon, String parentTraceParent, Callable<R> work) throws Exception {
        Span span = start(name, parentTraceParent)
                .tag(TenantContext.LOG_FIELD, salon.id().toString());
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            return work.call();
        } catch (Exception | Error ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Runs platform work that belongs to no salon (a platform job's run) in a new root span, so that
     * its log lines and platform audit entries carry a trace id. A failure is recorded and thrown on.
     */
    public <R> R inPlatformSpan(String name, Callable<R> work) throws Exception {
        Span span = start(name, null);
        try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
            return work.call();
        } catch (Exception | Error ex) {
            span.error(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /** Records on the current span, when there is one, that its work failed for this reason. */
    public void failed(Throwable failure) {
        Span span = tracer.currentSpan();
        if (span != null) {
            span.error(failure);
        }
    }

    /**
     * The work, made to run on another thread inside the span that is current here, so that its
     * log lines carry the same {@code trace.id} and {@code span.id}.
     */
    public <R> Callable<R> inCurrentSpan(Callable<R> work) {
        Span span = tracer.currentSpan();
        if (span == null) {
            return work;
        }
        return () -> {
            try (Tracer.SpanInScope _ = tracer.withSpan(span)) {
                return work.call();
            }
        };
    }

    private Span start(String name, String parentTraceParent) {
        if (parentTraceParent != null
                && RequestTrace.fromTraceParent(parentTraceParent).isPresent()) {
            return propagator
                    .extract(Map.of(RequestTrace.TRACE_PARENT, parentTraceParent), Map::get)
                    .name(name)
                    .start();
        }
        // A new trace: nothing of whatever happens to be current on this thread is its parent.
        try (Tracer.SpanInScope _ = tracer.withSpan(null)) {
            return tracer.nextSpan().name(name).start();
        }
    }
}
