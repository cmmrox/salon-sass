package com.salonplatform.platform.config;

import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.shared.web.RequestPaths;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The console's API answers on its exact hostname only (docs/architecture/operator-console.md):
 * {@code console.<platform domain>}, ignoring case and a valid port. Any other host (a salon, the bare
 * platform domain, a bare API address, another reserved name, a lookalike suffix or a nested label) is
 * answered 404 {@code not_found} before authentication, receipt lookup or any other side effect. The
 * {@code Host} header is read as received; forwarded headers never choose console authority. Every
 * answer of the console's API is {@code no-store}.
 *
 * <p>Runs after the tenant filter, which binds no salon for these paths and writes the request's log
 * line however it ends.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
class OperatorHostFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/v1/operator";

    private final String consoleHost;
    private final HandlerExceptionResolver errors;

    OperatorHostFilter(
            @Value("${salon.platform-domain}") String platformDomain,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
        this.consoleHost = "console." + platformDomain.strip().toLowerCase(Locale.ROOT);
        this.errors = errors;
    }

    /**
     * Filters every operator path, and fails closed on a path that is not canonical (which the tenant
     * filter has already refused): only a canonical path outside the console's API is let through.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return RequestPaths.canonical(request) && !RequestPaths.under(request, PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (!RequestPaths.canonical(request) || !isConsoleHost(request.getHeader(HttpHeaders.HOST), consoleHost)) {
            errors.resolveException(request, response, null, new NotFoundException());
            return;
        }
        chain.doFilter(request, response);
    }

    /** Exactly the console host, any letter case, with no port or one valid decimal port. */
    static boolean isConsoleHost(String host, String consoleHost) {
        if (host == null) {
            return false;
        }
        String value = host.strip().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        String name = colon < 0 ? value : value.substring(0, colon);
        if (colon >= 0) {
            String port = value.substring(colon + 1);
            if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) {
                return false;
            }
        }
        return name.equals(consoleHost);
    }
}
