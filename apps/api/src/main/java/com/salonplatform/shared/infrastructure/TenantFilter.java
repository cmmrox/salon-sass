package com.salonplatform.shared.infrastructure;

import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.api.RequestPaths;
import com.salonplatform.shared.audit.SupportRequestAudit;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.PlatformHostname;
import com.salonplatform.shared.tenancy.RequestCredential;
import com.salonplatform.shared.tenancy.RequestSupport;
import com.salonplatform.shared.tenancy.SignedTenantVerifier;
import com.salonplatform.shared.tenancy.SupportCredentials;
import com.salonplatform.shared.tenancy.SupportGrant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the salon a request's hostname names for the extent of the request, before anything that
 * could open a transaction runs.
 *
 * <p>The salon comes from the {@code Host} header and nothing else: not from a forwarded header,
 * not from a query parameter, not from any other header a client chose (T-5). The header is read as
 * received, so that no forwarded-header handling the server might be configured with can stand in
 * for it.
 *
 * <p>On a salon's hostname the salon is looked up as the request starts, so that every log line of
 * the request carries its {@code tenant.id}; a failure to look it up does not fail the request
 * here, and is reported by whatever needs the salon. The service's own endpoints are the exception
 * ({@link #isPlatformEndpoint}).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantFilter extends OncePerRequestFilter {

    private final PlatformHostname hostnames;

    private final TenantDirectory directory;

    private final SignedTenantVerifier verifier;
    private final ActiveTenants tenants;
    // Resolved per request, not when the server starts its filters: they reach the persistence layer.
    private final org.springframework.beans.factory.ObjectProvider<SupportCredentials> support;
    private final org.springframework.beans.factory.ObjectProvider<SupportRequestAudit> supportAudit;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    @org.springframework.context.annotation.Lazy
    private org.springframework.web.servlet.HandlerMapping routes;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
    private org.springframework.web.servlet.HandlerExceptionResolver errors;

    TenantFilter(
            PlatformHostname hostnames,
            TenantDirectory directory,
            SignedTenantVerifier verifier,
            ActiveTenants tenants,
            org.springframework.beans.factory.ObjectProvider<SupportCredentials> support,
            org.springframework.beans.factory.ObjectProvider<SupportRequestAudit> supportAudit) {
        this.support = support;
        this.supportAudit = supportAudit;
        this.verifier = verifier;
        this.tenants = tenants;
        this.hostnames = hostnames;
        this.directory = directory;
    }

    /**
     * The service's own endpoints, which need no salon and answer whether or not the database does
     * (docs/engineering/backend.md): the salon is not looked up for them, so their log lines carry
     * no {@code tenant.id}.
     */
    static boolean isPlatformEndpoint(HttpServletRequest request) {
        String path = RequestPaths.path(request);
        return path != null
                && (path.equals("/api/v1/meta")
                        || path.equals("/actuator")
                        || path.startsWith("/actuator/")
                        || path.startsWith("/v3/api-docs")
                        || path.equals("/error"));
    }

    /**
     * The operator console's API and its local hooks (docs/architecture/operator-console.md). They
     * belong to no salon: the hostname is never resolved as one, and the bearer credential is never
     * taken for a salon's. Platform authorizes them itself, on the exact console hostname only.
     */
    static boolean isOperatorEndpoint(HttpServletRequest request) {
        return RequestPaths.under(request, "/api/v1/operator") || RequestPaths.under(request, "/local/operator");
    }

    /**
     * A read-only salon (F03-S02) refuses every tenant mutation outside the allowlist before anything
     * runs: 403 {@code salon_read_only}, nothing changed ({@link ReadOnlyRoutes}).
     */
    private static void refuseWhenReadOnly(HttpServletRequest request) {
        if (ReadOnlyRoutes.refused(request)) {
            throw new ApplicationProblemException(403, ErrorCode.SALON_READ_ONLY);
        }
    }

    /**
     * The one mutation a support credential may make: ending its own session (F03-S04). Every other
     * mutating request under a support credential is refused 403 {@code support_read_only} here, as a
     * single rule on the principal's kind, before any handler runs.
     */
    static boolean supportMayChange(HttpServletRequest request) {
        return "DELETE".equals(request.getMethod()) && "/api/v1/support/session".equals(RequestPaths.path(request));
    }

    /**
     * Serves a request made under a live support session: refuses every mutation but ending it, then
     * records the request (method, route template, outcome) in the salon's audit trail.
     */
    private void underSupport(
            SupportGrant grant,
            String credential,
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws Exception {
        RequestSupport.callAs(grant, () -> {
            try {
                if (ReadOnlyRoutes.mutating(request.getMethod()) && !supportMayChange(request)) {
                    throw new ApplicationProblemException(403, ErrorCode.SUPPORT_READ_ONLY);
                }
                refuseWhenReadOnly(request);
                RequestCredential.callWith(credential, () -> {
                    chain.doFilter(request, response);
                    return null;
                });
            } catch (ApplicationProblemException | NotFoundException ex) {
                errors.resolveException(request, response, null, ex);
            } finally {
                SupportRequestAudit audit = supportAudit.getIfAvailable();
                if (audit != null) {
                    audit.record(grant, request.getMethod(), routeOf(request), response.getStatus());
                }
            }
            return null;
        });
    }

    /** The route template the request matched (never its raw path, which carries ids). */
    private String routeOf(HttpServletRequest request) {
        Object pattern =
                request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            try {
                // A request refused before dispatch: the route it would have reached.
                if (routes.getHandler(request) != null) {
                    pattern = request.getAttribute(
                            org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                }
            } catch (Exception ex) {
                pattern = null;
            }
        }
        return pattern == null ? "unmatched" : pattern.toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;
        String host = request.getHeader(HttpHeaders.HOST);
        String candidateSlug = hostnames.slugOf(host).orElse(null);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String credential =
                authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : "";
        try {
            // A path that is not already canonical could dispatch to a handler while every path
            // check below reads it differently (;parameters, percent-escapes, dot or empty
            // segments): it is refused before anything else (RequestPaths).
            if (!RequestPaths.canonical(request)) {
                throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST);
            }
            // Only a raw API address may use token-only tenancy. Unknown/reserved salon hosts
            // remain closed.
            String name = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
            boolean nativeAddress = name.matches("^(localhost|[0-9]{1,3}(\\.[0-9]{1,3}){3})(:[0-9]+)?$")
                    || name.matches("^\\[[0-9a-f:]+\\](:[0-9]+)?$");
            String path = RequestPaths.path(request);
            boolean publicPath = path.contains("/sign-in")
                    || path.equals("/api/v1/identity/customers/challenges")
                    || path.contains("/mfa/")
                    || path.endsWith("/sessions/refresh")
                    || RequestPaths.under(request, "/local");
            if (isOperatorEndpoint(request)) {
                TenantContext.callForRequest(
                        null,
                        directory,
                        () -> RequestCredential.callWith(credential, () -> {
                            chain.doFilter(request, response);
                            return null;
                        }));
            } else if (candidateSlug == null
                    && nativeAddress
                    && !credential.isBlank()
                    && !isPlatformEndpoint(request)
                    && !publicPath) {
                CurrentTenant tenant =
                        tenants.findActive(verifier.tenantOf(credential)).orElseThrow(NotFoundException::new);
                TenantContext.callAs(tenant, () -> {
                    refuseWhenReadOnly(request);
                    return RequestCredential.callWith(credential, () -> {
                        chain.doFilter(request, response);
                        return null;
                    });
                });
            } else {
                TenantContext.callForRequest(candidateSlug, directory, () -> {
                    if (candidateSlug != null && !isPlatformEndpoint(request)) TenantContext.resolveEarly();
                    // A caller supplying a signed identity must match even a public salon
                    // endpoint.
                    if (!credential.isBlank()
                            && !isPlatformEndpoint(request)
                            && !RequestPaths.under(request, "/local")) {
                        // A support credential (F03-S04) is verified against its live
                        // session and bound to this salon only; it never becomes a staff one.
                        SupportCredentials supports = support.getIfAvailable();
                        Optional<SupportGrant> grant = supports == null
                                ? Optional.empty()
                                : supports.verify(
                                        credential,
                                        () -> TenantContext.require().id());
                        if (grant.isPresent()) {
                            underSupport(grant.get(), credential, request, response, chain);
                            return null;
                        }
                        if (!verifier.tenantOf(credential)
                                .equals(TenantContext.require().id())) throw new NotFoundException();
                    }
                    refuseWhenReadOnly(request);
                    RequestCredential.callWith(credential, () -> {
                        chain.doFilter(request, response);
                        return null;
                    });
                    return null;
                });
            }
        } catch (ApplicationProblemException | NotFoundException ex) {
            errors.resolveException(request, response, null, ex);
        } catch (RuntimeException ex) {
            if (errors.resolveException(request, response, null, ex) == null) {
                failed = true;
                throw ex;
            }
        } catch (IOException | ServletException ex) {
            failed = true;
            throw ex;
        } catch (Exception ex) {
            failed = true;
            throw new ServletException(ex);
        } finally {
            try {
                int status = failed && !response.isCommitted() ? 500 : response.getStatus();
                RequestLog.writeIfMissing(request, status, System.nanoTime() - started);
            } finally {
                // The salon's id was put on the request's log lines when it was resolved.
                MDC.remove(TenantContext.LOG_FIELD);
            }
        }
    }
}
