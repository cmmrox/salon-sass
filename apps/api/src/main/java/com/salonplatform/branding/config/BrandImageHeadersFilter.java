package com.salonplatform.branding.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every answer to a brand image request, an image or a refusal alike, carries the salon hostname's no-store
 * caching and {@code X-Content-Type-Options: nosniff} (F04-S02 AC-7), set before anything else answers so
 * that no path through the request can leave them out.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class BrandImageHeadersFilter extends OncePerRequestFilter {

    private static final String IMAGES = "/api/v1/salon/brand/images/";

    /** The web's salon-hostname caching (docs/engineering/web.md): nothing about a salon is stored. */
    private static final String NOT_STORED = "private, no-cache, no-store, max-age=0, must-revalidate";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(IMAGES);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, NOT_STORED);
        response.setHeader("X-Content-Type-Options", "nosniff");
        chain.doFilter(request, response);
    }
}
