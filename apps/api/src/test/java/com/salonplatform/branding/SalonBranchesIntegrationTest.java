package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-1 of F04-S01: every existing salon has exactly one default branch after V18, a salon the operator
 * creates has one from the transaction that creates it, and {@link SalonBranches} answers for the current
 * salon only.
 */
class SalonBranchesIntegrationTest extends IntegrationTest {

    @Autowired
    SalonBranches branches;

    @Autowired
    OperatorAccountService operators;

    @Autowired
    OperatorCrypto operatorCrypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    Clock clock;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private final TestSalons salons = new TestSalons(owner);

    // --- SalonBranches ---------------------------------------------------------------------------------------

    @Test
    void theDefaultBranchIsTheCurrentSalonsOwn() throws Exception {
        TestSalon alpha = salons.create("Branches Alpha", "#111111");
        TestSalon beta = salons.create("Branches Beta", "#222222");

        SalonBranch alphas = TenantContext.callAs(salons.asCurrent(alpha), () -> branches.defaultBranch());
        SalonBranch betas = TenantContext.callAs(salons.asCurrent(beta), () -> branches.defaultBranch());

        assertThat(alphas).isEqualTo(new SalonBranch(salons.defaultBranch(alpha), "Branches Alpha", true));
        assertThat(betas.id()).isEqualTo(salons.defaultBranch(beta)).isNotEqualTo(alphas.id());
    }

    @Test
    void aBranchOfAnotherSalonAndOneThatDoesNotExistAreBothNotTheCurrentSalons() throws Exception {
        TestSalon alpha = salons.create("Belongs Alpha", "#111111");
        TestSalon beta = salons.create("Belongs Beta", "#222222");
        UUID alphas = salons.defaultBranch(alpha);
        UUID betas = salons.defaultBranch(beta);

        Map<String, Boolean> answers = TenantContext.callAs(
                salons.asCurrent(alpha),
                () -> Map.of(
                        "own", branches.belongsToCurrentSalon(alphas),
                        "other salon's", branches.belongsToCurrentSalon(betas),
                        "unknown", branches.belongsToCurrentSalon(UUID.randomUUID()),
                        "none", branches.belongsToCurrentSalon(null)));

        assertThat(answers)
                .containsEntry("own", true)
                .containsEntry("other salon's", false)
                .containsEntry("unknown", false)
                .containsEntry("none", false);
    }

    @Test
    void withNoSalonThereIsNoDefaultBranch() {
        TestSalon withoutBranch = salons.createWithoutBrand("active");

        assertThatExceptionOfType(TenantNotFoundException.class)
                .isThrownBy(
                        () -> TenantContext.callAs(salons.asCurrent(withoutBranch), () -> branches.defaultBranch()));
    }

    // --- provisioning ------------------------------------------------------------------------------------------

    @Test
    void aSalonTheOperatorCreatesHasItsDefaultBranchAndEmptyDraftsFromTheStart() {
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        String admin = console.token(console.create("OPERATOR_ADMIN"));
        Http.Exchange plan = console.callOn(
                console.host(),
                "POST",
                "/plans",
                OperatorTestSupport.plan(OperatorTestSupport.uniqueKey("br")),
                admin,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(plan.status()).withFailMessage("%s", plan.body()).isEqualTo(201);
        String slug = OperatorTestSupport.uniqueKey("br");
        Http.Exchange created = console.callOn(
                console.host(),
                "POST",
                "/salons",
                OperatorTestSupport.salon(
                        slug,
                        (String) console.body(plan).get("id"),
                        "owner-" + slug + "@example.test",
                        "synthetic owner " + UUID.randomUUID()),
                admin,
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(created.status()).withFailMessage("%s", created.body()).isEqualTo(201);
        UUID salon = UUID.fromString((String) console.body(created).get("id"));

        List<Map<String, Object>> rows =
                owner.queryForList("SELECT id, name, is_default FROM branding_branch WHERE tenant_id = ?", salon);
        assertThat(rows)
                .singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("name", "Synthetic Salon " + slug)
                        .containsEntry("is_default", true));
        assertThat(owner.queryForObject(
                        "SELECT version FROM branding_profile_draft WHERE tenant_id = ?", Long.class, salon))
                .isZero();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM branding_branch_contact_draft WHERE tenant_id = ? AND branch_id = ?",
                        Integer.class,
                        salon,
                        rows.getFirst().get("id")))
                .isEqualTo(1);
    }

    // --- the backfill ------------------------------------------------------------------------------------------

    @Test
    void theMigrationGivesEveryExistingSalonExactlyOneDefaultBranchUnderForcedRowLevelSecurity() {
        // The migrations' owner here is an ordinary role, so forced row-level security holds for the
        // backfill as it does in every environment but a developer's machine.
        TestDatabase database = TestDatabase.createEmptyOwnedByAnOrdinaryRole(TestPostgres.shared());
        migrate(database, MigrationVersion.fromVersion("17"));
        JdbcTemplate superuser = database.asOwner();
        UUID branded = UUID.randomUUID();
        UUID named = UUID.randomUUID();
        UUID bare = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        superuser.update("INSERT INTO tenant (id, slug, status) VALUES (?, 'backfill-branded', 'active')", branded);
        superuser.update(
                "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (?, 'Branded Salon', '#111111')",
                branded);
        superuser.update(
                "INSERT INTO tenant (id, slug, status, name) VALUES (?, 'backfill-named', 'suspended', 'Named Salon')",
                named);
        superuser.update("INSERT INTO tenant (id, slug, status) VALUES (?, 'backfill-bare', 'active')", bare);
        superuser.update("""
                INSERT INTO tenant (id, slug, status, closed_at, delete_at, deleted_at)
                VALUES (?, 'backfill-deleted', 'deleted', now() - interval '40 days', now() - interval '10 days', now())
                """, deleted);

        migrate(database, MigrationVersion.LATEST);

        assertThat(superuser.queryForList("""
                        SELECT t.slug, b.name, count(*) OVER (PARTITION BY b.tenant_id) AS per_salon
                          FROM branding_branch b JOIN tenant t ON t.id = b.tenant_id WHERE b.is_default ORDER BY t.slug
                        """))
                .containsExactly(
                        Map.of("slug", "backfill-bare", "name", "backfill-bare", "per_salon", 1L),
                        Map.of("slug", "backfill-branded", "name", "Branded Salon", "per_salon", 1L),
                        Map.of("slug", "backfill-named", "name", "Named Salon", "per_salon", 1L));
        assertThat(superuser.queryForObject("SELECT count(*) FROM branding_branch WHERE NOT is_default", Long.class))
                .isZero();
        assertThat(superuser.queryForObject(
                        "SELECT count(*) FROM branding_profile_draft WHERE version = 0", Long.class))
                .isEqualTo(3);
        assertThat(superuser.queryForObject("SELECT count(*) FROM branding_branch_contact_draft", Long.class))
                .isEqualTo(3);
        assertThat(superuser.queryForObject(
                        "SELECT count(*) FROM branding_branch WHERE tenant_id = ?", Long.class, deleted))
                .isZero();

        // Running the backfill's function again for a salon changes nothing it already has.
        superuser.queryForList("SELECT branding_add_default_branch(?, 'Another name', now())::text", branded);
        assertThat(superuser.queryForList(
                        "SELECT name FROM branding_branch WHERE tenant_id = ?", String.class, branded))
                .containsExactly("Branded Salon");
        // And the database refuses a second default branch however it is written.
        assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> superuser.update(
                        "INSERT INTO branding_branch (tenant_id, id, name, is_default, created_at, updated_at)"
                                + " VALUES (?, ?, 'Second', true, now(), now())",
                        branded,
                        UUID.randomUUID()));
    }

    private static void migrate(TestDatabase database, MigrationVersion target) {
        Flyway.configure()
                .dataSource(database.url(), TestDatabase.ORDINARY_OWNER, TestDatabase.ORDINARY_OWNER_PASSWORD)
                .placeholders(
                        Map.of("application_role", TestPostgres.APP_USER, "platform_role", TestPostgres.PLATFORM_USER))
                .target(target)
                .load()
                .migrate();
    }
}
