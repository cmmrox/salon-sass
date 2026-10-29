package com.salonplatform.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class MetaEndpointIntegrationTest extends IntegrationTest {

    @Test
    void returnsTheServiceNameVersionCommitAndTime() throws IOException {
        HttpResponse<String> response = http().get("/api/v1/meta");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Http.contentType(response)).startsWith("application/json");
        Map<String, Object> body = JsonPath.parse(response.body()).read("$");
        assertThat(body).containsOnlyKeys("service", "version", "commit", "time");
        assertThat(body.get("service")).isEqualTo("salon-api");
        assertThat(body.get("version")).isEqualTo(written("META-INF/build-info.properties", "build.version"));
        assertThat(body.get("commit")).isEqualTo(written("git.properties", "git.commit.id"));
    }

    @Test
    void timeIsAnIsoInstantInUtc() {
        HttpResponse<String> response = http().get("/api/v1/meta");

        String time = JsonPath.parse(response.body()).read("$.time");
        assertThat(time).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z");
        assertThat(Instant.parse(time)).isCloseTo(Instant.now(), within(Duration.ofSeconds(30)));
    }

    /**
     * What the build wrote, read the way the application reads it. A build with no repository
     * writes no commit, and the endpoint then says "unknown".
     */
    private static String written(String resource, String key) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = MetaEndpointIntegrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in != null) {
                properties.load(in);
            }
        }
        String value = properties.getProperty(key, "unknown");
        assertThat(value).as("%s in %s", key, resource).isNotBlank();
        return value;
    }
}
