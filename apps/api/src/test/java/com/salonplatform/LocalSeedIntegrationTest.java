package com.salonplatform;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * The local seed: the three synthetic salons of docs/qa/test-plan.md, in the {@code local} profile
 * only, and the same however often the application starts.
 */
class LocalSeedIntegrationTest {

    private static final String LOCAL = "--spring.profiles.active=local";

    private static final String SALONS = """
            SELECT t.id::text AS id, t.slug, t.status, b.display_name, b.brand_colour,
                   t.created_at AS tenant_created, t.updated_at AS tenant_updated,
                   b.created_at AS brand_created, b.updated_at AS brand_updated
              FROM tenant t LEFT JOIN salon_brand b ON b.tenant_id = t.id
             ORDER BY t.slug
            """;

    @Test
    void theLocalProfileSeedsExactlyTheThreeSyntheticSalons() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());

        try (StartedApplication application = database.start(LOCAL)) {
            assertThat(database.asOwner().queryForList(SALONS))
                    .extracting(row -> row.get("id"), row -> row.get("slug"), row -> row.get("status"),
                            row -> row.get("display_name"), row -> row.get("brand_colour"))
                    .containsExactly(
                            tuple("a1a1a1a1-0000-4000-8000-000000000001", "alpha", "active", "Alpha Studio", "#D6086A"),
                            tuple("b2b2b2b2-0000-4000-8000-000000000002", "beta", "active",
                                    "Beta Hair, Beauty & Wellness Lounge of Colombo Seven", "#1F2937"),
                            tuple("c3c3c3c3-0000-4000-8000-000000000003", "gamma", "active", "Gamma", "#FDE68A"));

            Exchange beta = application.http().getOnHost("beta.localhost:" + application.port(), "/api/v1/salon");
            assertThat(beta.status()).isEqualTo(200);
            assertThat(JsonPath.parse(beta.body()).<Map<String, Object>>read("$"))
                    .containsEntry("slug", "beta")
                    .containsEntry("name", "Beta Hair, Beauty & Wellness Lounge of Colombo Seven")
                    .containsEntry("brandColour", "#1F2937");
        }
    }

    @Test
    void startingAgainNeitherDuplicatesNorChangesASalonNorOverwritesOneThatWasEdited() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication first = database.start(LOCAL)) {
            // Seeded.
        }
        database.asOwner().update("UPDATE salon_brand SET display_name = 'Gamma, renamed' WHERE tenant_id = "
                + "'c3c3c3c3-0000-4000-8000-000000000003'");
        List<Map<String, Object>> before = database.asOwner().queryForList(SALONS);

        try (StartedApplication second = database.start(LOCAL)) {
            // Seeded again.
        }
        try (StartedApplication third = database.start(LOCAL)) {
            assertThat(database.asOwner().queryForList(SALONS)).isEqualTo(before).hasSize(3);
        }
        assertThat(before).extracting(row -> row.get("display_name")).contains("Gamma, renamed");
    }

    @Test
    void anyOtherProfileCreatesTheSchemaAndNoSalon() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());

        try (StartedApplication application = database.start()) {
            assertThat(database.asOwner().queryForObject("SELECT count(*) FROM tenant", Integer.class)).isZero();
            assertThat(database.asOwner().queryForObject("SELECT count(*) FROM salon_brand", Integer.class)).isZero();
            assertThat(application.http().getOnHost("alpha.localhost:" + application.port(), "/api/v1/salon").status())
                    .isEqualTo(404);
        }
    }

    /** A database the local profile seeded still starts under another profile: the seed leaves no history. */
    @Test
    void aSeededDatabaseStartsUnderAnotherProfileToo() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication local = database.start(LOCAL)) {
            // Seeded.
        }

        try (StartedApplication other = database.start()) {
            assertThat(other.http().get("/actuator/health").statusCode()).isEqualTo(200);
            assertThat(database.asOwner().queryForObject("SELECT count(*) FROM tenant", Integer.class)).isEqualTo(3);
        }
    }
}
