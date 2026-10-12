package com.salonplatform.platform;

import java.util.Optional;
import java.util.UUID;

import com.salonplatform.shared.tenancy.TenantDirectory;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The tenant registry. The database refuses a slug that could never be a salon's subdomain, as the
 * owning role too, and the registry answers only for active salons.
 */
class TenantTableIntegrationTest {

    private static StartedApplication application;

    private static JdbcTemplate owner;

    @BeforeAll
    static void migrated() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        application = database.start();
        owner = database.asOwner();
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // reserved: the platform's own hostnames
        "admin", "api", "app", "apps", "assets", "auth", "cdn", "console", "docs", "help", "login", "mail", "staging",
        "static", "status", "support", "www",
        // too short, too long
        "", "a", "ab", "abcdefghijklmnopqrstuvwxyz12345",
        // not lowercase letters, digits and hyphens
        "Alpha", "ALPHA", "alphA", "al_pha", "al.pha", "al pha", "alpha!", "alpha/x", "álpha", "alpha\u0000",
        // beginning or ending with a hyphen
        "-alpha", "alpha-", "-al-",
    })
    void aSlugThatCannotBeASalonsSubdomainIsRefused(String slug) {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), slug, "active"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "abc", "a-b", "a1b", "123", "salon-7", "abcdefghijklmnopqrstuvwxyz1234", "admins", "wwww" })
    void aSlugOfLowercaseLettersDigitsAndHyphensOfThreeToThirtyIsAccepted(String slug) {
        insert(UUID.randomUUID(), slug, "active");

        assertThat(owner.queryForObject("SELECT count(*) FROM tenant WHERE slug = ?", Integer.class, slug)).isEqualTo(1);
    }

    @Test
    void aSecondSalonWithTheSameSlugIsRefused() {
        insert(UUID.randomUUID(), "twin", "active");

        assertThatExceptionOfType(DuplicateKeyException.class).isThrownBy(() -> insert(UUID.randomUUID(), "twin", "active"));
    }

    @Test
    void theIdThatMeansNoSalonAndAnUnknownStatusAreRefused() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(new UUID(0L, 0L), "nil-salon", "active"));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> insert(UUID.randomUUID(), "odd-status", "closed"));
    }

    @Test
    void theRegistryAnswersForAnActiveSalonOnly() {
        UUID active = UUID.randomUUID();
        UUID suspended = UUID.randomUUID();
        insert(active, "open-salon", "active");
        insert(suspended, "closed-salon", "suspended");
        TenantDirectory registry = application.bean(TenantDirectory.class);

        assertThat(registry.findActive("open-salon")).contains(active);
        assertThat(registry.findActive("closed-salon")).isEmpty();
        assertThat(registry.findActive("no-such-salon")).isEmpty();
        assertThat(registry.findActive("OPEN-SALON")).as("slugs are stored and looked up in lower case").isEqualTo(Optional.empty());
    }

    private static void insert(UUID id, String slug, String status) {
        owner.update("INSERT INTO tenant (id, slug, status) VALUES (?, ?, ?)", id, slug, status);
    }
}
