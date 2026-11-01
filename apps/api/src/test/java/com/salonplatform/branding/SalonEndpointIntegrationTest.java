package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.ProblemBodies;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code GET /api/v1/salon} through HTTP, with both isolation layers in place: the salon comes from
 * the hostname and nowhere else, and each salon sees its own brand only.
 */
class SalonEndpointIntegrationTest extends IntegrationTest {

    private static final String SALON = "/api/v1/salon";

    private TestSalon alpha;

    private TestSalon beta;

    private TestSalon gamma;

    @BeforeEach
    void salons() {
        TestSalons salons =
                new TestSalons(TestDatabase.of(TestPostgres.shared()).asOwner());
        alpha = salons.create("Alpha Studio", "#D6086A");
        beta = salons.create("Beta Hair, Beauty & Wellness Lounge of Colombo Seven", "#1F2937");
        gamma = salons.create("Gamma", "#FDE68A", ZoneId.of("Europe/London"));
    }

    @Test
    void eachHostnameGetsItsOwnSalonsBrandAndNothingThatIdentifiesItBeyondThat() {
        for (TestSalon salon : List.of(alpha, beta, gamma)) {
            Exchange response = http().getOnHost(salon.host(port()), SALON);

            assertThat(response.status()).as(salon.slug()).isEqualTo(200);
            assertThat(response.contentType()).startsWith("application/json");
            Map<String, Object> body = JsonPath.parse(response.body()).read("$");
            assertThat(body).containsOnlyKeys("slug", "name", "brandColour", "timeZone", "profile");
            // F04-S01: nothing is published yet, so there is no profile to serve.
            assertThat(body).containsEntry("profile", null);
            assertThat(body)
                    .containsEntry("slug", salon.slug())
                    .containsEntry("name", salon.name())
                    .containsEntry("brandColour", salon.brandColour())
                    .containsEntry("timeZone", salon == gamma ? "Europe/London" : "Asia/Colombo");
            assertThat(response.body()).doesNotContain(salon.id().toString());
        }
    }

    @Test
    void theHostnameIsMatchedWithoutRegardToCaseAndWithoutItsPort() {
        Exchange upper = http().getOnHost(alpha.slug().toUpperCase() + ".LOCALHOST:" + port(), SALON);
        Exchange noPort = http().getOnHost(alpha.slug() + ".localhost", SALON);

        assertThat(upper.status()).isEqualTo(200);
        assertThat(JsonPath.parse(upper.body()).<String>read("$.slug")).isEqualTo(alpha.slug());
        assertThat(noPort.status()).isEqualTo(200);
        assertThat(JsonPath.parse(noPort.body()).<String>read("$.slug")).isEqualTo(alpha.slug());
    }

    @Test
    void aHostnameThatNamesNoSalonGetsTheSameNotFoundEveryTimeWhilePlatformEndpointsStillAnswer() {
        TestSalon withoutBrand =
                new TestSalons(TestDatabase.of(TestPostgres.shared()).asOwner()).createWithoutBrand("active");
        List<String> hosts = List.of(
                "localhost:" + port(),
                "nosuchsalon.localhost:" + port(),
                "console.localhost:" + port(),
                "www.localhost:" + port(),
                alpha.slug() + "." + beta.slug() + ".localhost:" + port(),
                alpha.slug() + ".localhost.example.com:" + port(),
                "127.0.0.1:" + port(),
                withoutBrand.host(port()));

        String first = null;
        for (String host : hosts) {
            Exchange response = http().getOnHost(host, SALON);

            assertThat(response.status()).as(host).isEqualTo(404);
            assertThat(response.contentType()).as(host).startsWith("application/problem+json");
            DocumentContext body = JsonPath.parse(response.body());
            assertThat(body.<String>read("$.code")).as(host).isEqualTo("not_found");
            assertThat(body.<String>read("$.instance")).as(host).isEqualTo(SALON);
            assertThat(body.<String>read("$.traceId")).as(host).matches("^[0-9a-f]{32}$");
            String withoutTrace = ProblemBodies.withoutTraceId(response.body());
            if (first == null) {
                first = withoutTrace;
            }
            assertThat(withoutTrace)
                    .as("the body on %s is the body on every other, but for its trace id", host)
                    .isEqualTo(first);

            assertThat(http().getOnHost(host, "/api/v1/meta").status())
                    .as("meta on %s", host)
                    .isEqualTo(200);
            assertThat(http().getOnHost(host, "/actuator/health").status())
                    .as("health on %s", host)
                    .isEqualTo(200);
        }
    }

    /** T-5: a salon named by the client is never trusted. Only the Host header counts. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "X-Forwarded-Host",
                "X-Tenant-Id",
                "X-Tenant",
                "X-Salon",
                "Forwarded",
                "X-Original-Host",
                "X-Host"
            })
    void aSalonNamedInAnyOtherHeaderIsIgnored(String header) {
        for (String value : new String[] {
            beta.slug() + ".localhost:" + port(),
            beta.id().toString(),
            beta.slug(),
            "host=" + beta.slug() + ".localhost"
        }) {
            Exchange response = http().getOnHost(alpha.host(port()), SALON, Map.of(header, value));

            assertThat(response.status()).as("%s: %s", header, value).isEqualTo(200);
            assertThat(JsonPath.parse(response.body()).<String>read("$.slug"))
                    .as("%s: %s", header, value)
                    .isEqualTo(alpha.slug());
        }
    }

    @Test
    void aSalonNamedInTheQueryIsIgnored() {
        for (String query : new String[] {
            "?tenant=" + beta.slug(),
            "?tenantId=" + beta.id(),
            "?salon=" + beta.slug(),
            "?slug=" + beta.slug(),
            "?host=" + beta.slug() + ".localhost"
        }) {
            Exchange response = http().getOnHost(alpha.host(port()), SALON + query);

            assertThat(response.status()).as(query).isEqualTo(200);
            assertThat(JsonPath.parse(response.body()).<String>read("$.slug"))
                    .as(query)
                    .isEqualTo(alpha.slug());
        }
    }

    /** An absolute request target that names another salon than the Host header is refused, never served. */
    @Test
    void aRequestTargetThatContradictsTheHostHeaderIsRefused() {
        String response = http().raw(
                        "127.0.0.1",
                        "GET http://" + beta.host(port()) + SALON + " HTTP/1.1\r\n" + "Host: " + alpha.host(port())
                                + "\r\nConnection: close\r\n\r\n");

        assertThat(response).startsWith("HTTP/1.1 400");
        assertThat(response).doesNotContain(beta.name()).doesNotContain(alpha.name());
    }

    /**
     * Requests for three salons at once, on virtual threads sharing a small pool: every answer is the
     * salon of its own hostname. A pooled connection or a reused thread carrying a salon over would
     * show up here as a mismatch.
     */
    @Test
    void simultaneousRequestsForThreeSalonsEachGetTheirOwn() throws Exception {
        List<TestSalon> salons = List.of(alpha, beta, gamma);
        int requests = 300;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> mismatches = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                TestSalon salon = salons.get(i % salons.size());
                mismatches.add(pool.submit(() -> {
                    start.await();
                    Exchange response = http().getOnHost(salon.host(port()), SALON);
                    if (response.status() != 200) {
                        return salon.slug() + " got " + response.status();
                    }
                    DocumentContext body = JsonPath.parse(response.body());
                    boolean own = salon.slug().equals(body.read("$.slug"))
                            && salon.name().equals(body.read("$.name"))
                            && salon.brandColour().equals(body.read("$.brandColour"));
                    return own ? null : salon.slug() + " got " + response.body();
                }));
            }
            start.countDown();
            List<String> found = new ArrayList<>();
            for (Future<String> mismatch : mismatches) {
                if (mismatch.get() != null) {
                    found.add(mismatch.get());
                }
            }
            assertThat(found).isEmpty();
        }
    }
}
