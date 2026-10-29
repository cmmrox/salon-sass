package com.salonplatform.shared.web;

import com.salonplatform.shared.tenancy.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which requests a read-only salon refuses (docs/architecture/operator-console.md, "Read-only"): every
 * mutating request to a tenant endpoint under {@code /api}, except an explicit allowlist that keeps
 * sign-in, MFA, session renewal, sign-out (one's own sessions), password change and customer privacy
 * rights. A new mutating route is refused by default; reads are never refused. The local test hooks
 * ({@code /local}) are not product routes and are not refused.
 */
final class ReadOnlyRoutes {

    private record Exempt(String method, Pattern path) {}

    private static final String UUID = "[0-9a-fA-F-]{1,64}";

    private static final List<Exempt> EXEMPT = List.of(
            exempt("POST", "/api/v1/identity/staff/sign-in"),
            exempt("POST", "/api/v1/identity/staff/mfa/challenges"),
            exempt("POST", "/api/v1/identity/staff/mfa/enroll"),
            exempt("POST", "/api/v1/identity/staff/mfa/verify"),
            exempt("POST", "/api/v1/identity/sessions/refresh"),
            exempt("DELETE", "/api/v1/identity/sessions"),
            exempt("DELETE", "/api/v1/identity/sessions/" + UUID),
            exempt("PUT", "/api/v1/identity/staff/password"),
            exempt("POST", "/api/v1/identity/customers/challenges"),
            exempt("POST", "/api/v1/identity/customers/sign-in"),
            exempt("PUT", "/api/v1/identity/customers/me"),
            exempt("DELETE", "/api/v1/identity/customers/me"),
            // Support sessions (F03-S04) are allowed for a read-only salon: entering one and ending
            // it change nothing of the salon.
            exempt("POST", "/api/v1/support/entry"),
            exempt("DELETE", "/api/v1/support/session"),
            // The Owner can always take support access away, as staff can always end their own sessions.
            exempt("DELETE", "/api/v1/salon/support-sessions/" + UUID));

    private ReadOnlyRoutes() {}

    private static Exempt exempt(String method, String path) {
        return new Exempt(method, Pattern.compile(path));
    }

    /** Whether the method changes something: everything but GET, HEAD and OPTIONS. */
    static boolean mutating(String method) {
        return !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
    }

    /** Whether this request is allowed while its salon is read-only. */
    static boolean allowed(HttpServletRequest request) {
        String method = request.getMethod();
        if (!mutating(method) || !RequestPaths.under(request, "/api")) {
            return true;
        }
        // The billing provider's event intake belongs to no salon (it answers on the platform host only).
        if (RequestPaths.under(request, "/api/v1/billing")) {
            return true;
        }
        String path = RequestPaths.path(request);
        return EXEMPT.stream()
                .anyMatch(each -> each.method().equals(method)
                        && each.path().matcher(path).matches());
    }

    /**
     * Whether the request must be refused: its salon has been resolved, is read-only, and the request
     * is not allowed. A salon not resolved yet (the registry could not be read) is refused later by
     * whatever requires it.
     */
    static boolean refused(HttpServletRequest request) {
        return TenantContext.resolved()
                .map(salon -> salon.readOnly() && !allowed(request))
                .orElse(false);
    }
}
