package com.salonplatform.shared.infrastructure;

import java.io.IOException;

import com.salonplatform.shared.tenancy.PlatformHostname;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String candidateSlug = hostnames.slugOf(request.getHeader(HttpHeaders.HOST)).orElse(null);
        try {
            TenantContext.callForRequest(candidateSlug, directory, () -> {
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
    }
}
