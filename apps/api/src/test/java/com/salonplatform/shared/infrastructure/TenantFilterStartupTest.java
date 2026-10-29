package com.salonplatform.shared.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.PlatformHostname;
import com.salonplatform.shared.tenancy.SignedTenantVerifier;
import com.salonplatform.shared.tenancy.TenantDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

class TenantFilterStartupTest {
    private final PlatformHostname hostnames = mock(PlatformHostname.class);
    private final TenantDirectory directory = mock(TenantDirectory.class);
    private final SignedTenantVerifier verifier = mock(SignedTenantVerifier.class);
    private final ActiveTenants tenants = mock(ActiveTenants.class);

    private ApplicationContextRunner nonWeb() {
        return new ApplicationContextRunner()
                .withUserConfiguration(TenantFilter.class)
                .withBean(PlatformHostname.class, () -> hostnames)
                .withBean(TenantDirectory.class, () -> directory)
                .withBean(SignedTenantVerifier.class, () -> verifier)
                .withBean(ActiveTenants.class, () -> tenants);
    }

    private WebApplicationContextRunner servlet() {
        return new WebApplicationContextRunner()
                .withUserConfiguration(TenantFilter.class)
                .withBean(PlatformHostname.class, () -> hostnames)
                .withBean(TenantDirectory.class, () -> directory)
                .withBean(SignedTenantVerifier.class, () -> verifier)
                .withBean(ActiveTenants.class, () -> tenants);
    }

    @Test
    void nonWebMigrationContextNeedsNoServletFilterOrResolver() {
        nonWeb().run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .doesNotHaveBean(TenantFilter.class)
                    .doesNotHaveBean(HandlerExceptionResolver.class);
        });
    }

    @Test
    void servletContextStillRequiresItsNamedResolver() {
        servlet()
                .run(context ->
                        assertThat(context).hasFailed().getFailure().hasMessageContaining("handlerExceptionResolver"));
    }

    @Test
    void servletFilterResolvesTenantFailuresInsteadOfBypassingSecurity() {
        HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
        servlet()
                .withBean("handlerExceptionResolver", HandlerExceptionResolver.class, () -> resolver)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TenantFilter.class);
                    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/salon");
                    request.addHeader("Host", "unknown.example.test");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    NotFoundException denial = new NotFoundException();
                    context.getBean(TenantFilter.class)
                            .doFilter(request, response, (ignoredRequest, ignoredResponse) -> {
                                throw denial;
                            });
                    verify(resolver).resolveException(same(request), same(response), isNull(), same(denial));
                });
    }
}
