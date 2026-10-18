package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.testing.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.*;

/** Fixture/reset boundaries on a separately running local-profile product. */
class LocalIdentityIntegrationTest {
    @TempDir Path directory;

    @Test
    void localSeedIsIdempotentSecretFreeAndResetsOnlyItsOwnSalon() throws Exception {
        TestDatabase db = TestDatabase.createEmpty(TestPostgres.shared());
        String password = UUID.randomUUID() + " synthetic";
        String secret;
        try (StartedApplication app =
                db.start(
                        "--spring.profiles.active=test,local",
                        "--spring.flyway.locations=classpath:db/migration,classpath:db/local",
                        "--salon.identity.local-key-file="
                                + directory.resolve("keys.properties"))) {
            secret = app.bean(IdentityCrypto.class).newTotpSecret();
            JsonMapper json = app.bean(JsonMapper.class);
            List<Map<String, Object>> accounts = new ArrayList<>();
            for (String role :
                    List.of("OWNER", "MANAGER", "RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT")) {
                Map<String, Object> a = new HashMap<>();
                a.put("email", role.toLowerCase(Locale.ROOT) + "@example.test");
                a.put("password", password);
                a.put("role", role);
                if (role.equals("OWNER")) a.put("totpSecret", secret);
                accounts.add(a);
            }
            String payload = json.writeValueAsString(Map.of("accounts", accounts));
            for (String salon : List.of("alpha", "beta", "gamma")) {
                for (int i = 0; i < 2; i++) {
                    var response =
                            app.http()
                                    .postJsonOnHost(
                                            salon + ".localhost:" + app.port(),
                                            "/local/identity/seed",
                                            payload);
                    assertThat(response.status()).isEqualTo(200);
                    assertThat(response.body()).doesNotContain(password, secret, "@example.test");
                }
            }
            assertThat(
                            db.asOwner()
                                    .queryForObject(
                                            "SELECT count(*) FROM identity_staff", Long.class))
                    .isEqualTo(15);
            // F03-S02: the fixtures also work for a provisioned salon, and only for its own records.
            String foreign =
                    new TestSalons(db.asOwner()).create("Other", "#111111").host(app.port());
            assertThat(
                            app.http()
                                    .postJsonOnHost(
                                            foreign,
                                            "/local/identity/seed",
                                            json.writeValueAsString(
                                                    Map.of("accounts", List.of(accounts.get(1)))))
                                    .status())
                    .isEqualTo(200);
            assertThat(
                            db.asOwner()
                                    .queryForObject(
                                            "SELECT count(*) FROM identity_staff", Long.class))
                    .isEqualTo(16);
            assertThat(
                            app.http()
                                    .sendOnHost(
                                            "DELETE", foreign, "/local/identity", Map.of(), null)
                                    .status())
                    .isEqualTo(204);
            assertThat(
                            db.asOwner()
                                    .queryForObject(
                                            "SELECT count(*) FROM identity_staff", Long.class))
                    .isEqualTo(15);
            assertThat(
                            app.http()
                                    .sendOnHost(
                                            "DELETE",
                                            "alpha.localhost:" + app.port(),
                                            "/local/identity",
                                            Map.of(),
                                            null)
                                    .status())
                    .isEqualTo(204);
            assertThat(
                            db.asOwner()
                                    .queryForObject(
                                            "SELECT count(*) FROM identity_staff", Long.class))
                    .isEqualTo(10);
            assertThat(app.http().get("/v3/api-docs").body()).doesNotContain("/local/identity");
        }
        try (StartedApplication app = db.start()) {
            assertThat(
                            app.http()
                                    .postJsonOnHost(
                                            "beta.localhost:" + app.port(),
                                            "/local/identity/seed",
                                            "{}")
                                    .status())
                    .isEqualTo(404);
        }
    }
}
