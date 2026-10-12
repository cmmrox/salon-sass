package com.salonplatform.shared.infrastructure;

import java.io.IOException;

import com.salonplatform.shared.api.RequestTrace;
import com.salonplatform.shared.tenancy.TenantContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * For every request: records its trace id (the id of the span Spring Boot opened for it, which
 * continues a W3C {@code traceparent} the caller sent), so that every error answer carries it;
 * writes the request's one log line when it ends ({@link RequestLog}); and puts the salon's id on
 * the request's span when the request turned out to be a salon's.
 *
 * <p>It runs inside {@link TenantFilter}, so the salon is still known when the line is written.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class RequestLogFilter extends OncePerRequestFilter {

    private final ObjectProvider<Tracer> tracer;

    RequestLogFilter(ObjectProvider<Tracer> tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        Span span = currentSpan();
        String traceId = span != null && RequestTrace.isTraceId(span.context().traceId()) ? span.context().traceId()
                : RequestTrace.of(request);
        request.setAttribute(RequestTrace.ATTRIBUTE, traceId);
        // Without a span nothing else puts the trace id on the request's lines; this does.
        boolean ownTraceField = MDC.get(RequestLog.TRACE_ID) == null;
        if (ownTraceField) {
            MDC.put(RequestLog.TRACE_ID, traceId);
        }
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        }
        catch (IOException | ServletException | RuntimeException ex) {
            failed = true;
            throw ex;
        }
        finally {
            int status = failed && !response.isCommitted() ? 500 : response.getStatus();
            if (span != null) {
                TenantContext.resolved().ifPresent(salon -> span.tag(TenantContext.LOG_FIELD, salon.id().toString()));
            }
            RequestLog.write(request.getMethod(), request.getRequestURI(), status, System.nanoTime() - started);
            request.setAttribute(RequestLog.LOGGED, Boolean.TRUE);
            if (ownTraceField) {
                MDC.remove(RequestLog.TRACE_ID);
            }
        }
    }

    private Span currentSpan() {
        Tracer available = tracer.getIfAvailable();
        return available == null ? null : available.currentSpan();
    }
}
