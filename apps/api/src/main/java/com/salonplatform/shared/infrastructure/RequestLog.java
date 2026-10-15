package com.salonplatform.shared.infrastructure;

import java.util.concurrent.TimeUnit;

import com.salonplatform.shared.api.RequestTrace;
import com.salonplatform.shared.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * The one log line each API request writes (NFR-16): its method, its path without the query
 * string, its status and its duration, as ECS fields and as the message. Nothing else of the
 * request is written: no body, no query string, no header (cookies and authorisation included).
 * The salon's {@code tenant.id} and the request's {@code trace.id} come with the line from the
 * logging context.
 */
final class RequestLog {

    /** Set on a request once its line is written, so that it is written once. */
    static final String LOGGED = RequestLog.class.getName() + ".logged";

    static final String TRACE_ID = "trace.id";

    static final String SPAN_ID = "span.id";

    private static final Logger log = LoggerFactory.getLogger("com.salonplatform.request");

    private RequestLog() {
    }

    /**
     * Records a request denied before the inner logging filter ran. The problem response has
     * already recorded its trace; only an actually bound salon supplies tenant context. Never
     * resolve a salon for logging, or retain MDC left behind by an unwound tenant scope.
     */
    static void writeIfMissing(HttpServletRequest request, int status, long durationNanos) {
        if (request.getAttribute(LOGGED) != null) {
            return;
        }
        String outerTrace = MDC.get(TRACE_ID);
        String outerTenant = MDC.get(TenantContext.LOG_FIELD);
        MDC.put(TRACE_ID, RequestTrace.of(request));
        TenantContext.resolved().ifPresentOrElse(
                tenant -> MDC.put(TenantContext.LOG_FIELD, tenant.id().toString()),
                () -> MDC.remove(TenantContext.LOG_FIELD));
        try {
            write(request.getMethod(), request.getRequestURI(), status, durationNanos);
            request.setAttribute(LOGGED, Boolean.TRUE);
        }
        finally {
            restore(TRACE_ID, outerTrace);
            restore(TenantContext.LOG_FIELD, outerTenant);
        }
    }

    private static void restore(String field, String value) {
        if (value == null) {
            MDC.remove(field);
        }
        else {
            MDC.put(field, value);
        }
    }

    static void write(String method, String path, int status, long durationNanos) {
        log.atInfo()
                .addKeyValue("http.request.method", method)
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("event.duration", durationNanos)
                .log("{} {} {} {} ms", method, path, status, TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }
}
