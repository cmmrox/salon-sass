package com.salonplatform.shared.infrastructure;

import java.io.IOException;

import com.salonplatform.shared.tenancy.PlatformHostname;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;

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
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class TenantFilter extends OncePerRequestFilter {

    private final PlatformHostname hostnames;

    private final TenantDirectory directory;

    TenantFilter(PlatformHostname hostnames, TenantDirectory directory) {
        this.hostnames = hostnames;
        this.directory = directory;
    }

    /**
     * The service's own endpoints, which need no salon and answer whether or not the database
     * does (docs/engineering/backend.md): the salon is not looked up for them, so their log lines
     * carry no {@code tenant.id}.
     */
    static boolean isPlatformEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/api/v1/meta") || path.equals("/actuator") || path.startsWith("/actuator/")
                || path.startsWith("/v3/api-docs") || path.equals("/error");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidateSlug = hostnames.slugOf(request.getHeader(HttpHeaders.HOST)).orElse(null);
        try {
            TenantContext.callForRequest(candidateSlug, directory, () -> {
                if (candidateSlug != null && !isPlatformEndpoint(request)) {
                    TenantContext.resolveEarly();
                }
                chain.doFilter(request, response);
                return null;
            });
        }
        catch (IOException | ServletException | RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new ServletException(ex);
        }
        finally {
            // The salon's id was put on the request's log lines when it was resolved.
            MDC.remove(TenantContext.LOG_FIELD);
        }
    }
}
