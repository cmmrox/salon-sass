package com.salonplatform.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A request's trace id: 32 lowercase hexadecimal characters, the same in every problem body and on
 * every log line of the request (NFR-16). The request filter records the id of the request's span
 * as an attribute when the request starts, so that every error answer, including one written after
 * the span has ended, carries the same id. A request the server refused before any span existed
 * takes the trace id of a valid W3C {@code traceparent} it carried, or a new one.
 */
public final class RequestTrace {

    /** The request attribute holding the trace id. */
    public static final String ATTRIBUTE = RequestTrace.class.getName() + ".traceId";

    /** The W3C trace context header. */
    public static final String TRACE_PARENT = "traceparent";

    /** version 00, a trace id, a parent id, flags; neither id all zeros (W3C Trace Context). */
    private static final Pattern TRACE_PARENT_FORMAT =
            Pattern.compile("^00-(?!0{32})([0-9a-f]{32})-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$");

    private static final Pattern TRACE_ID_FORMAT = Pattern.compile("^(?!0{32})[0-9a-f]{32}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestTrace() {}

    /** The request's trace id: the one recorded for it, or else one that can stand for it. */
    public static String of(HttpServletRequest request) {
        if (request.getAttribute(ATTRIBUTE) instanceof String recorded && isTraceId(recorded)) {
            return recorded;
        }
        String traceId = fromTraceParent(request.getHeader(TRACE_PARENT)).orElseGet(RequestTrace::newTraceId);
        request.setAttribute(ATTRIBUTE, traceId);
        return traceId;
    }

    /** The trace id of a valid {@code traceparent} header. */
    public static Optional<String> fromTraceParent(String header) {
        if (header == null) {
            return Optional.empty();
        }
        Matcher matcher = TRACE_PARENT_FORMAT.matcher(header.trim());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    public static boolean isTraceId(String candidate) {
        return candidate != null && TRACE_ID_FORMAT.matcher(candidate).matches();
    }

    public static String newTraceId() {
        byte[] bytes = new byte[16];
        String id;
        do {
            RANDOM.nextBytes(bytes);
            id = HexFormat.of().formatHex(bytes);
        } while (!isTraceId(id));
        return id;
    }
}
