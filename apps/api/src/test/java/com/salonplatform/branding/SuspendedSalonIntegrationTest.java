package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A suspended salon, through HTTP: its hostname answers 403 {@code salon_suspended} with nothing of
 * its brand, the salon beside it is not touched, and the platform's own endpoints still answer.
 */
class SuspendedSalonIntegrationTest extends IntegrationTest {

    private static final String SALON = "/api/v1/salon";

    private TestSalons salons;

    private TestSalon open;

    private TestSalon closed;

    @BeforeEach
    void twoSalons() {
        salons = new TestSalons(TestDatabase.of(TestPostgres.shared()).asOwner());
        open = salons.create("Open Studio", "#D6086A");
        closed = salons.create("Closed Lounge", "#1F2937");
    }

    @Test
    void aSuspendedSalonAnswersForbiddenWithItsCodeAndNothingOfItsBrand() {
        salons.suspend(closed);

        Exchange response = http().getOnHost(closed.host(port()), SALON);

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.contentType()).startsWith("application/problem+json");
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        assertThat(body).containsOnlyKeys("title", "status", "detail", "instance", "code", "traceId");
        assertThat(body)
                .containsEntry("code", "salon_suspended")
                .containsEntry("status", 403)
                .containsEntry("title", "Forbidden")
                .containsEntry("instance", SALON);
        assertThat(response.body())
                .as("nothing of the suspended salon")
                .doesNotContain(closed.name())
                .doesNotContain(closed.brandColour())
                .doesNotContain(closed.slug())
                .doesNotContain(closed.id().toString())
                .doesNotContain("brandColour");
    }

    @Test
    void suspendingOneSalonChangesNothingForTheOther() {
        Exchange before = http().getOnHost(open.host(port()), SALON);

        salons.suspend(closed);

        Exchange after = http().getOnHost(open.host(port()), SALON);
        assertThat(after.status()).isEqualTo(200);
        assertThat(after.body()).isEqualTo(before.body());
        assertThat(JsonPath.parse(after.body()).<Map<String, Object>>read("$"))
                .containsEntry("slug", open.slug())
                .containsEntry("name", open.name())
                .containsEntry("brandColour", open.brandColour());
        assertThat(after.body()).doesNotContain(closed.name()).doesNotContain(closed.brandColour());
    }

    /** Naming the open salon in a header or the query from the suspended one's hostname opens nothing. */
    @Test
    void aSuspendedSalonCannotBorrowAnotherSalonFromTheClient() {
        salons.suspend(closed);

        List<Exchange> responses = List.of(
                http().getOnHost(closed.host(port()), SALON, Map.of("X-Forwarded-Host", open.host(port()))),
                http().getOnHost(
                                closed.host(port()),
                                SALON,
                                Map.of("X-Tenant-Id", open.id().toString())),
                http().getOnHost(closed.host(port()), SALON + "?salon=" + open.slug() + "&tenantId=" + open.id()));

        for (Exchange response : responses) {
            assertThat(response.status()).isEqualTo(403);
            assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("salon_suspended");
            assertThat(response.body()).doesNotContain(open.name()).doesNotContain(closed.name());
        }
    }

    @Test
    void thePlatformsOwnEndpointsStillAnswerOnASuspendedSalonsHostname() {
        salons.suspend(closed);

        assertThat(http().getOnHost(closed.host(port()), "/api/v1/meta").status())
                .isEqualTo(200);
        assertThat(http().getOnHost(closed.host(port()), "/actuator/health").status())
                .isEqualTo(200);
    }

    /** Nothing about a salon's status is kept between requests: the next request sees the change. */
    @Test
    void aStatusChangeIsSeenOnTheNextRequestInBothDirections() {
        assertThat(http().getOnHost(closed.host(port()), SALON).status()).isEqualTo(200);

        salons.suspend(closed);
        assertThat(http().getOnHost(closed.host(port()), SALON).status()).isEqualTo(403);
        assertThat(http().getOnHost(closed.host(port()), SALON).status()).isEqualTo(403);

        salons.activate(closed);
        Exchange again = http().getOnHost(closed.host(port()), SALON);
        assertThat(again.status()).isEqualTo(200);
        assertThat(JsonPath.parse(again.body()).<String>read("$.name")).isEqualTo(closed.name());
    }

    /** Suspended is told from missing: a salon that does not exist is still 404 {@code not_found}. */
    @Test
    void aSalonThatDoesNotExistIsStillNotFoundAndNotSuspended() {
        salons.suspend(closed);

        Exchange response = http().getOnHost("nosuchsalon.localhost:" + port(), SALON);

        assertThat(response.status()).isEqualTo(404);
        assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("not_found");
    }
}
