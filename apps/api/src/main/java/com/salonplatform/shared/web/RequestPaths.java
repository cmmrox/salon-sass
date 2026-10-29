package com.salonplatform.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The one way the API decides which path a request is for, wherever that decides authority (the
 * console's hostname check, the operator endpoints, the local hooks, the sign-in paths).
 *
 * <p>The servlet container and Spring MVC dispatch on a decoded, normalized path with path
 * parameters removed, while {@code getRequestURI()} is the raw text the client sent. A check on the
 * raw text could therefore be skipped by a request that dispatches to the same handler
 * ({@code /api/v1/operator;x=1/plans}, {@code /api/v1/%6Fperator/plans}, {@code //api/v1/...},
 * dot segments). Rather than enumerate such spellings, a request whose raw path is not already in
 * its one canonical form is refused before anything else runs ({@link #canonical}): then the raw
 * path is exactly the path that is dispatched, and {@link #path} and {@link #under} are safe to use.
 * No API path needs a percent-escape: ids are UUIDs and every other segment is a fixed word or slug.
 */
public final class RequestPaths {

    private RequestPaths() {}

    /**
     * Whether the raw path is canonical: it starts with {@code /}, has no path parameter
     * ({@code ;}), no percent-escape, no backslash, and no empty, {@code .} or {@code ..} segment
     * (a single trailing slash is allowed; it simply matches no handler).
     */
    public static boolean canonical(HttpServletRequest request) {
        String raw = request.getRequestURI();
        if (raw == null
                || raw.isEmpty()
                || raw.charAt(0) != '/'
                || raw.indexOf(';') >= 0
                || raw.indexOf('%') >= 0
                || raw.indexOf('\\') >= 0) {
            return false;
        }
        String[] segments = raw.substring(1).split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            boolean trailing = i == segments.length - 1;
            if ((segment.isEmpty() && !trailing && segments.length > 1)
                    || segment.equals(".")
                    || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /** The request's path when it is canonical (and so is the dispatched path); otherwise null. */
    public static String path(HttpServletRequest request) {
        return canonical(request) ? request.getRequestURI() : null;
    }

    /** Whether the canonical path is the prefix itself or below it. A non-canonical path is never. */
    public static boolean under(HttpServletRequest request, String prefix) {
        String path = path(request);
        return path != null && (path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    /** Whether the canonical path is exactly this one. */
    public static boolean is(HttpServletRequest request, String exact) {
        return exact.equals(path(request));
    }

    /** The export download path carries a credential in its next segment (F03-S03). */
    private static final java.util.regex.Pattern EXPORT_TOKEN =
            java.util.regex.Pattern.compile("/api/v1/exports/[^/?#]+");

    /**
     * A path or URL as it may be logged or traced: an export download's token is replaced by
     * {@code {token}}, so no download credential reaches a log line or a span.
     */
    public static String loggable(String path) {
        return path == null ? null : EXPORT_TOKEN.matcher(path).replaceAll("/api/v1/exports/{token}");
    }
}
