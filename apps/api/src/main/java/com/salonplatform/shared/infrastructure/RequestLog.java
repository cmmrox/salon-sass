package com.salonplatform.shared.infrastructure;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    static void write(String method, String path, int status, long durationNanos) {
        log.atInfo()
                .addKeyValue("http.request.method", method)
                .addKeyValue("url.path", path)
                .addKeyValue("http.response.status_code", status)
                .addKeyValue("event.duration", durationNanos)
                .log("{} {} {} {} ms", method, path, status, TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }
}
