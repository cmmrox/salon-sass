package com.salonplatform.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The salons that exist before the catalogue's migration (F05-S01 AC-1, decision 093): every salon that is not
 * deleted, whatever its status or access (active, suspended, read-only), gets the ten default categories exactly
 * once on its default branch; a deleted salon gets none. Migrated to the version before the catalogue, given its
 * salons as the earlier migrations and provisioning leave them, then migrated to the latest.
 */
class CatalogueDefaultCategoriesMigrationIntegrationTest {

    private static final MigrationVersion BEFORE_THE_CATALOGUE = MigrationVersion.fromVersion("24");

    private static TestDatabase database;
    private static JdbcTemplate owner;
    private static final UUID ACTIVE = UUID.randomUUID();
    private static final UUID SUSPENDED = UUID.randomUUID();
    private static final UUID READ_ONLY = UUID.randomUUID();
    private static final UUID DELETED = UUID.randomUUID();

    @BeforeAll
    static void salonsBeforeTheCatalogueThenTheMigration() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        owner = database.asOwner();
        migrate(BEFORE_THE_CATALOGUE);
        salon(ACTIVE, "active");
        salon(SUSPENDED, "suspended");
        salon(READ_ONLY, "active");
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", READ_ONLY);
        // A deleted salon with the branch it had while it was open: its data is a tombstone all the same.
        salon(DELETED, "active");
        owner.update(
                "UPDATE tenant SET status = 'deleted', closed_at = now() - interval '40 days',"
                        + " delete_at = now() - interval '10 days', deleted_at = now() WHERE id = ?",
                DELETED);
        migrate(MigrationVersion.LATEST);
    }

    /** A salon as provisioning leaves it before F05: a brand, its default branch and its drafts. */
    private static void salon(UUID id, String status) {
        owner.update("INSERT INTO tenant (id, slug, status) VALUES (?, ?, ?)", id, slug(id), status);
        owner.update(
                "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (?, 'Before', '#111827')", id);
        owner.queryForList("SELECT branding_add_brand_draft(?, now())::text", id);
        owner.queryForList("SELECT branding_add_default_branch(?, 'Before', now())::text", id);
    }

    private static String slug(UUID id) {
        return "m" + id.toString().replace("-", "").substring(0, 12);
    }

    private static void migrate(MigrationVersion target) {
        Flyway.configure()
                .dataSource(
                        database.url(),
                        database.container().getUsername(),
                        database.container().getPassword())
                .placeholders(
                        Map.of("application_role", TestPostgres.APP_USER, "platform_role", TestPostgres.PLATFORM_USER))
                .target(target)
                .load()
                .migrate();
    }

    private static List<String> categories(UUID salon) {
        return owner.queryForList(
                "SELECT c.name FROM catalogue_category c JOIN branding_branch b ON b.tenant_id = c.tenant_id"
                        + " AND b.id = c.branch_id AND b.is_default WHERE c.tenant_id = ? ORDER BY c.position",
                String.class,
                salon);
    }

    @Test
    void everySalonThatIsNotDeletedHasTheTenDefaultsOnItsDefaultBranch() {
        for (UUID salon : List.of(ACTIVE, SUSPENDED, READ_ONLY)) {
            assertThat(categories(salon))
                    .as("salon %s", salon)
                    .containsExactlyElementsOf(CatalogueFixture.DEFAULT_CATEGORIES);
            assertThat(owner.queryForObject(
                            "SELECT count(*) FROM catalogue_category_list WHERE tenant_id = ?", Integer.class, salon))
                    .isEqualTo(1);
        }
    }

    @Test
    void aDeletedSalonGetsNone() {
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM catalogue_category WHERE tenant_id = ?", Integer.class, DELETED))
                .isZero();
    }

    @Test
    void givingTheDefaultsAgainAddsNothing() {
        owner.queryForList("SELECT catalogue_add_default_categories(?, now())::text", ACTIVE);

        assertThat(categories(ACTIVE)).hasSize(10);
    }
}
