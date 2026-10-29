package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.identity.inprocess.PreparedOwner;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.testing.IntegrationTest;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Only identity prepares a salon's first Owner (docs/architecture/operator-console.md, "Platform database
 * boundary and atomic bootstrap"): the password is checked and hashed inside identity, and the bootstrap
 * refuses an Owner that identity did not prepare, so a caller cannot bootstrap an Owner with a hash of
 * its own choosing.
 */
class OwnerBootstrapIntegrationTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery staple 42";

    @Autowired
    IdentityProvisioning provisioning;

    @Autowired
    PlatformDatabase platform;

    @Test
    void refusesAnOwnerThatIdentityDidNotPrepareInsideThePlatformTransaction() {
        UUID salon = UUID.randomUUID();
        PreparedOwner forged = forgedOwner();

        assertThatThrownBy(() ->
                        platform.inTransaction(status -> provisioning.bootstrapOwner(salon, forged, Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        Long staff = platform.inTransaction(status -> platform.jdbc()
                .sql("SELECT identity_active_staff_count(?)")
                .param(salon)
                .query(Long.class)
                .single());
        assertThat(staff).isZero();
    }

    @Test
    void refusesAnOwnerThatIdentityDidNotPrepareBeforeLookingForATransaction() {
        PreparedOwner forged = forgedOwner();

        assertThatThrownBy(() -> provisioning.bootstrapOwner(UUID.randomUUID(), forged, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPreparedOwnerShowsItsNameAndEmailButNeverItsHash() {
        PreparedOwner prepared =
                provisioning.prepareOwner("owner.", " Nadeesha Perera ", "Owner@Example.TEST", PASSWORD);

        assertThat(prepared.name()).isEqualTo("Nadeesha Perera");
        assertThat(prepared.email()).isEqualTo("owner@example.test");
        assertThat(prepared.toString())
                .doesNotContain("Nadeesha")
                .doesNotContain("example.test")
                .doesNotContain("pbkdf2")
                .doesNotContain(PASSWORD);
        assertThat(Arrays.stream(PreparedOwner.class.getMethods()).map(Method::getName))
                .containsExactlyInAnyOrder("name", "email");
    }

    /** A caller's own PreparedOwner: the name and email of a real one, and no hash identity checked. */
    private static PreparedOwner forgedOwner() {
        return new PreparedOwner() {
            @Override
            public String name() {
                return "Forged Owner";
            }

            @Override
            public String email() {
                return "forged@example.test";
            }
        };
    }
}
