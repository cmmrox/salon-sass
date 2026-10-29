package com.salonplatform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The staging profile behind the edge (F01-S05): X-Forwarded-Proto and X-Forwarded-For are believed
 * from the edge's network only, and X-Forwarded-Host never, so the salon is still the one {@code Host}
 * names (T-5). Two applications: one that counts the test's own address (loopback) as the edge's
 * network, and one that does not.
 */
class ForwardedHeadersIntegrationTest {

    private static final Map<String, String> FORWARDED = Map.of(
            "X-Forwarded-Proto", "https",
            "X-Forwarded-For", "203.0.113.7");

    private static TestDatabase database;

    private static StartedApplication behindTheEdge;

    private static StartedApplication notFromTheEdge;

    private static TestSalon alpha;

    private static TestSalon beta;

    /** Added to the applications under test: shows what the server made of a request. */
    @Configuration(proxyBeanMethods = false)
    static class RequestEcho {

        @Bean
        RouterFunction<ServerResponse> forwardedEcho() {
            return RouterFunctions.route()
                    .GET("/test/forwarded", request -> {
                        HttpServletRequest servlet = request.servletRequest();
                        Map<String, Object> seen = new LinkedHashMap<>();
                        seen.put("scheme", servlet.getScheme());
                        seen.put("secure", servlet.isSecure());
                        seen.put("remoteAddr", servlet.getRemoteAddr());
                        seen.put("serverName", servlet.getServerName());
                        seen.put("host", servlet.getHeader("Host"));
                        return ServerResponse.ok().body(seen);
                    })
                    .build();
        }
    }

    @BeforeAll
    static void start() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        behindTheEdge = database.startWith(RequestEcho.class, staging("127.0.0.0/8"));
        notFromTheEdge = database.startWith(RequestEcho.class, staging("10.255.255.0/24"));
        TestSalons salons = new TestSalons(database.asOwner());
        alpha = salons.create("Alpha Forwarded", "#D6086A");
        beta = salons.create("Beta Forwarded", "#1F2937");
    }

    private static String[] staging(String edgeNetwork) {
        return new String[] {
            "--spring.profiles.active=staging",
            "--spring.flyway.enabled=true",
            "--SALON_TRUSTED_PROXIES=" + edgeNetwork,
            "--SALON_OTLP_ENDPOINT=http://127.0.0.1:9",
            "--management.tracing.export.otlp.enabled=false",
            "--management.otlp.metrics.export.enabled=false",
        };
    }

    @AfterAll
    static void stop() {
        behindTheEdge.close();
        notFromTheEdge.close();
    }

    /** The forwarded headers, with X-Forwarded-Host naming this salon. */
    private static Map<String, String> forwardedFor(TestSalon salon, StartedApplication application) {
        Map<String, String> headers = new LinkedHashMap<>(FORWARDED);
        headers.put("X-Forwarded-Host", salon.slug() + ".localhost:" + application.port());
        return headers;
    }

    @Test
    void fromTheEdgesNetworkTheProtocolAndTheClientAreBelievedButNotTheHost() {
        String alphaHost = alpha.slug() + ".localhost:" + behindTheEdge.port();
        Exchange echo = behindTheEdge.http().getOnHost(alphaHost, "/test/forwarded", forwardedFor(beta, behindTheEdge));

        DocumentContext seen = JsonPath.parse(echo.body());
        assertThat(seen.<String>read("$.scheme")).isEqualTo("https");
        assertThat(seen.<Boolean>read("$.secure")).isTrue();
        assertThat(seen.<String>read("$.remoteAddr")).isEqualTo("203.0.113.7");
        assertThat(seen.<String>read("$.serverName")).isEqualTo(alpha.slug() + ".localhost");
        assertThat(seen.<String>read("$.host")).isEqualTo(alphaHost);
    }

    @Test
    void aForwardedHostNamingAnotherSalonStillShowsTheSalonTheHostNames() {
        Exchange salon = behindTheEdge
                .http()
                .getOnHost(
                        alpha.slug() + ".localhost:" + behindTheEdge.port(),
                        "/api/v1/salon",
                        forwardedFor(beta, behindTheEdge));

        assertThat(salon.status()).isEqualTo(200);
        assertThat(JsonPath.parse(salon.body()).<String>read("$.slug")).isEqualTo(alpha.slug());
        assertThat(salon.body()).doesNotContain(beta.slug()).doesNotContain("Beta Forwarded");
    }

    @Test
    void fromAnywhereElseNoForwardedHeaderIsBelieved() {
        Exchange echo = notFromTheEdge
                .http()
                .getOnHost(
                        alpha.slug() + ".localhost:" + notFromTheEdge.port(),
                        "/test/forwarded",
                        forwardedFor(beta, notFromTheEdge));

        DocumentContext seen = JsonPath.parse(echo.body());
        assertThat(seen.<String>read("$.scheme")).isEqualTo("http");
        assertThat(seen.<Boolean>read("$.secure")).isFalse();
        assertThat(seen.<String>read("$.remoteAddr")).isEqualTo("127.0.0.1");
        assertThat(seen.<String>read("$.serverName")).isEqualTo(alpha.slug() + ".localhost");
    }

    @Test
    void theStagingProfileHasNoLocalHookAndNoMeters() {
        for (String path : new String[] {"/local/clock", "/local/probes", "/actuator/metrics", "/actuator/info"}) {
            assertThat(behindTheEdge
                            .http()
                            .getOnHost("localhost:" + behindTheEdge.port(), path)
                            .status())
                    .as(path)
                    .isEqualTo(404);
        }
    }
}
