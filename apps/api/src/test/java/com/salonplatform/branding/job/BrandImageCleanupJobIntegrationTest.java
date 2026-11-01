package com.salonplatform.branding.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.domain.BrandImagesTest;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * F04-S02 AC-10 with the test clock: an image neither the draft nor the published brand has used for 24 hours
 * is removed, counted from when it stopped being used (or its upload, when it never was); an image in use is
 * never removed; and a run for one salon touches no other salon's images.
 */
// Its movable clock makes a context of its own, closed after this class so its pools free their slots.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BrandImageCleanupJobIntegrationTest extends IntegrationTest {

    static final MovableClock CLOCK = new MovableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }
    }

    @Autowired
    List<TenantJob> jobs;

    @Autowired
    IdentityCrypto identity;

    @Autowired
    JsonMapper json;

    @Autowired
    PlatformTransactionManager transactions;

    @PersistenceContext
    EntityManager entities;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private final TestSalons salons = new TestSalons(owner);
    private final String password = "synthetic manager " + UUID.randomUUID();

    private StaffAccount manager(TestSalon salon) throws Exception {
        return TenantContext.callAs(
                salons.asCurrent(salon),
                () -> new TransactionTemplate(transactions).execute(status -> {
                    StaffAccount account = StaffAccount.create(
                            "mgr-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test",
                            null,
                            identity.encodePassword(password),
                            StaffRole.MANAGER,
                            false,
                            CLOCK.instant());
                    entities.persist(account);
                    return account;
                }));
    }

    // JSON maps, read untyped.
    @SuppressWarnings("unchecked")
    private String token(TestSalon salon, StaffAccount account) {
        Http.Exchange answer = http().sendOnHost(
                        "POST",
                        salon.host(port()),
                        "/api/v1/identity/staff/sign-in",
                        Map.of("Content-Type", "application/json"),
                        json.writeValueAsString(Map.of(
                                "identifier",
                                account.getEmail(),
                                "password",
                                password,
                                "deviceLabel",
                                "JUnit cleanup")));
        assertThat(answer.status()).withFailMessage("%s", answer.body()).isEqualTo(200);
        Map<String, Object> body = json.readValue(answer.body(), Map.class);
        return (String) ((Map<String, Object>) body.get("credentials")).get("accessToken");
    }

    private String upload(TestSalon salon, String token, String kind) throws Exception {
        String boundary = "----cleanup";
        byte[] image =
                kind.equals("logo") ? BrandImagesTest.image(300, 300, "png") : BrandImagesTest.image(1000, 400, "jpeg");
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\n" + kind
                        + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"x\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        body.writeBytes(image);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Authorization", "Bearer " + token);
        Http.BinaryExchange answer = http().sendBytesOnHost(
                        "POST", salon.host(port()), "/api/v1/salon/brand/images", headers, body.toByteArray());
        assertThat(answer.status())
                .withFailMessage("%s", answer.asText().body())
                .isEqualTo(201);
        return (String) json.readValue(answer.asText().body(), Map.class).get("id");
    }

    private Http.Exchange send(TestSalon salon, String method, String path, Object request, String token) {
        return http().sendOnHost(
                        method,
                        salon.host(port()),
                        path,
                        Map.of("Content-Type", "application/json", "Authorization", "Bearer " + token),
                        json.writeValueAsString(request));
    }

    private void saveDraft(TestSalon salon, String token, long version, String logo, String cover) {
        Map<String, Object> brand = new HashMap<>();
        brand.put("name", "Cleanup Studio");
        brand.put("tagline", null);
        brand.put("colour", "#2F4B8A");
        brand.put("logo", logo);
        brand.put("cover", cover);
        Http.Exchange saved =
                send(salon, "PUT", "/api/v1/salon/brand/draft", Map.of("version", version, "brand", brand), token);
        assertThat(saved.status()).withFailMessage("%s", saved.body()).isEqualTo(200);
    }

    private void runCleanupFor(TestSalon salon) throws Exception {
        TenantJob cleanup = jobs.stream()
                .filter(job -> job.name().equals("branding-image-cleanup"))
                .findFirst()
                .orElseThrow();
        assertThat(cleanup.every()).isEqualTo(Duration.ofHours(1));
        TenantContext.callAs(
                salons.asCurrent(salon),
                () -> new TransactionTemplate(transactions).execute(status -> {
                    try {
                        cleanup.runFor(salons.asCurrent(salon), CLOCK.instant());
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                    return null;
                }));
    }

    private boolean exists(TestSalon salon, String id) {
        return owner.queryForObject(
                        "SELECT count(*) FROM branding_image WHERE tenant_id = ? AND id = ?::uuid",
                        Integer.class,
                        salon.id(),
                        id)
                == 1;
    }

    @Test
    void anImageUnusedFor24HoursIsRemovedAndOneInUseOrAnotherSalonsNeverIs() throws Exception {
        TestSalon alpha = salons.create("Cleanup Alpha", "#111111");
        TestSalon beta = salons.create("Cleanup Beta", "#222222");
        StaffAccount alphas = manager(alpha);
        StaffAccount betas = manager(beta);
        String token = token(alpha, alphas);
        String published = upload(alpha, token, "logo");
        String draftOnly = upload(alpha, token, "cover");
        String neverUsed = upload(alpha, token, "logo");
        String betasUnused = upload(beta, token(beta, betas), "logo");
        saveDraft(alpha, token, 0, published, null);
        assertThat(send(alpha, "POST", "/api/v1/salon/brand/publication", Map.of("version", 1), token)
                        .status())
                .isEqualTo(200);
        saveDraft(alpha, token, 2, published, draftOnly);

        CLOCK.advance(Duration.ofHours(23));
        runCleanupFor(alpha);
        assertThat(exists(alpha, neverUsed)).as("unused for 23 hours").isTrue();

        CLOCK.advance(Duration.ofHours(2));
        runCleanupFor(alpha);
        assertThat(exists(alpha, neverUsed)).as("unused for 25 hours").isFalse();
        assertThat(exists(alpha, published)).as("published").isTrue();
        assertThat(exists(alpha, draftOnly)).as("used by the draft").isTrue();
        assertThat(exists(beta, betasUnused))
                .as("another salon's, unused for 25 hours")
                .isTrue();

        // The draft stops using its cover now: the 24 hours count from here, not from its upload 25 hours ago.
        String later = token(alpha, alphas);
        saveDraft(alpha, later, 3, published, null);
        CLOCK.advance(Duration.ofHours(23));
        runCleanupFor(alpha);
        assertThat(exists(alpha, draftOnly))
                .as("unused for 23 hours since the draft dropped it")
                .isTrue();
        CLOCK.advance(Duration.ofHours(2));
        runCleanupFor(alpha);
        assertThat(exists(alpha, draftOnly))
                .as("unused for 25 hours since the draft dropped it")
                .isFalse();
        assertThat(exists(alpha, published)).isTrue();
        assertThat(exists(beta, betasUnused)).isTrue();
    }
}
