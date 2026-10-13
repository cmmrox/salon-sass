package com.salonplatform.shared.tenancy;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What a request learns about its salon from the tenant context: the salon, that there is none, or
 * that it is suspended (docs/architecture/multi-tenancy.md). No Spring and no database: the
 * registry is whatever the test says it is.
 */
class TenantContextTest {

    private static final UUID ID = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001");

    private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");

    private final AtomicInteger lookups = new AtomicInteger();

    private TenantDirectory registryWith(RegisteredTenant registered) {
        return slug -> {
            lookups.incrementAndGet();
            return "alpha".equals(slug) ? Optional.ofNullable(registered) : Optional.empty();
        };
    }

    @Test
    void anActiveSalonIsTheSalonOfTheRequestAndIsLookedUpOnce() throws Exception {
        TenantDirectory registry = registryWith(new RegisteredTenant(ID, TenantStatus.ACTIVE, ZONE));

        TenantContext.callForRequest("alpha", registry, () -> {
            assertThat(TenantContext.resolved()).as("nothing is resolved until it is needed").isEmpty();
            assertThat(TenantContext.require()).isEqualTo(new CurrentTenant(ID, "alpha", ZONE));
            assertThat(TenantContext.require()).isEqualTo(new CurrentTenant(ID, "alpha", ZONE));
            assertThat(TenantContext.resolved()).contains(new CurrentTenant(ID, "alpha", ZONE));
            return null;
        });

        assertThat(lookups).hasValue(1);
    }

    @Test
    void aSuspendedSalonIsRefusedAsSuspendedAndIsNeverTheSalonOfTheRequest() throws Exception {
        TenantDirectory registry = registryWith(new RegisteredTenant(ID, TenantStatus.SUSPENDED, ZONE));

        TenantContext.callForRequest("alpha", registry, () -> {
            assertThatExceptionOfType(TenantSuspendedException.class).isThrownBy(TenantContext::require);
            assertThat(TenantContext.resolved())
                    .as("the database is given no salon, so nothing of a suspended salon can be read").isEmpty();
            assertThatExceptionOfType(TenantSuspendedException.class).isThrownBy(TenantContext::require);
            return null;
        });

        assertThat(lookups).as("the answer is kept for the rest of the request").hasValue(1);
    }

    @Test
    void aSlugTheRegistryDoesNotKnowIsNotFoundAndNotSuspended() throws Exception {
        TenantDirectory registry = registryWith(new RegisteredTenant(ID, TenantStatus.SUSPENDED, ZONE));

        TenantContext.callForRequest("nosuchsalon", registry, () -> {
            assertThatExceptionOfType(TenantNotFoundException.class).isThrownBy(TenantContext::require);
            assertThat(TenantContext.resolved()).isEmpty();
            return null;
        });
    }

    @Test
    void aHostnameThatNamesNoSalonIsNotFoundWithoutAskingTheRegistry() throws Exception {
        TenantDirectory registry = registryWith(new RegisteredTenant(ID, TenantStatus.ACTIVE, ZONE));

        TenantContext.callForRequest(null, registry, () -> {
            assertThatExceptionOfType(TenantNotFoundException.class).isThrownBy(TenantContext::require);
            return null;
        });

        assertThat(lookups).hasValue(0);
    }

    @Test
    void outsideARequestThereIsNoSalon() {
        assertThatExceptionOfType(TenantNotFoundException.class).isThrownBy(TenantContext::require);
        assertThat(TenantContext.resolved()).isEmpty();
    }

    @Test
    void theAnswerOfOneRequestIsNotCarriedIntoTheNext() throws Exception {
        RegisteredTenant[] registered = { new RegisteredTenant(ID, TenantStatus.SUSPENDED, ZONE) };
        TenantDirectory registry = slug -> Optional.of(registered[0]);

        TenantContext.callForRequest("alpha", registry, () -> {
            assertThatExceptionOfType(TenantSuspendedException.class).isThrownBy(TenantContext::require);
            return null;
        });
        registered[0] = new RegisteredTenant(ID, TenantStatus.ACTIVE, ZONE);

        TenantContext.callForRequest("alpha", registry, () -> {
            assertThat(TenantContext.require().id()).isEqualTo(ID);
            return null;
        });
    }
}
