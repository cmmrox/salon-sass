package com.salonplatform.shared.infrastructure;

import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.PlatformHostname;
import com.salonplatform.shared.tenancy.RequestCredential;
import com.salonplatform.shared.tenancy.SignedTenantVerifier;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantDirectory;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

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

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("handlerExceptionResolver")
    private org.springframework.web.servlet.HandlerExceptionResolver errors;

    TenantFilter(
            PlatformHostname hostnames,
            TenantDirectory directory,
            SignedTenantVerifier verifier,
            ActiveTenants tenants) {
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
        String path = request.getRequestURI();
        return path.equals("/api/v1/meta")
                || path.equals("/actuator")
                || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs")
                || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;
        String host = request.getHeader(HttpHeaders.HOST);
        String candidateSlug = hostnames.slugOf(host).orElse(null);
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String credential =
                authorization != null && authorization.startsWith("Bearer ")
                        ? authorization.substring(7)
                        : "";
        try {
            // Only a raw API address may use token-only tenancy. Unknown/reserved salon hosts
            // remain closed.
            String name = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
            boolean nativeAddress =
                    name.matches("^(localhost|[0-9]{1,3}(\\.[0-9]{1,3}){3})(:[0-9]+)?$")
                            || name.matches("^\\[[0-9a-f:]+\\](:[0-9]+)?$");
            boolean publicPath =
                    request.getRequestURI().contains("/sign-in")
                            || request.getRequestURI()
                                    .equals("/api/v1/identity/customers/challenges")
                            || request.getRequestURI().contains("/mfa/")
                            || request.getRequestURI().endsWith("/sessions/refresh")
                            || request.getRequestURI().startsWith("/local/");
            if (candidateSlug == null
                    && nativeAddress
                    && !credential.isBlank()
                    && !isPlatformEndpoint(request)
                    && !publicPath) {
                CurrentTenant tenant =
                        tenants.findActive(verifier.tenantOf(credential))
                                .orElseThrow(NotFoundException::new);
                TenantContext.callAs(
                        tenant,
                        () ->
                                RequestCredential.callWith(
                                        credential,
                                        () -> {
                                            chain.doFilter(request, response);
                                            return null;
                                        }));
            } else {
                TenantContext.callForRequest(
                        candidateSlug,
                        directory,
                        () -> {
                            if (candidateSlug != null && !isPlatformEndpoint(request))
                                TenantContext.resolveEarly();
                            // A caller supplying a signed identity must match even a public salon
                            // endpoint.
                            if (!credential.isBlank()
                                    && !isPlatformEndpoint(request)
                                    && !request.getRequestURI().startsWith("/local/")) {
                                if (!verifier.tenantOf(credential)
                                        .equals(TenantContext.require().id()))
                                    throw new NotFoundException();
                            }
                            RequestCredential.callWith(
                                    credential,
                                    () -> {
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
            }
            finally {
                // The salon's id was put on the request's log lines when it was resolved.
                MDC.remove(TenantContext.LOG_FIELD);
            }
        }
    }
}
