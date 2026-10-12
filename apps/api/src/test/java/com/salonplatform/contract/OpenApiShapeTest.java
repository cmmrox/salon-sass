package com.salonplatform.contract;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the generated OpenAPI document promises its clients, whatever else changes: a published
 * path, operation or field is not renamed or removed (docs/engineering/backend.md).
 */
class OpenApiShapeTest extends IntegrationTest {

    @Test
    void metaIsDescribedWithEveryFieldRequired() {
        DocumentContext document = document();

        assertThat(document.<String>read("$.paths['/api/v1/meta'].get.operationId")).isEqualTo("getMeta");
        assertThat(document.<String>read(
                "$.paths['/api/v1/meta'].get.responses.200.content['application/json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Meta");
        assertThat(document.<List<String>>read("$.components.schemas.Meta.required"))
                .containsExactlyInAnyOrder("service", "version", "commit", "time");
        assertThat(document.<String>read("$.components.schemas.Meta.properties.time.format")).isEqualTo("date-time");
    }

    @Test
    void salonIsDescribedWithItsBrandAndItsNotFoundAnswer() {
        DocumentContext document = document();
        String salon = "$.paths['/api/v1/salon'].get";

        assertThat(document.<String>read(salon + ".operationId")).isEqualTo("getSalon");
        assertThat(document.<String>read(salon + ".responses.200.content['application/json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Salon");
        for (String status : new String[] { "404", "503" }) {
            assertThat(document.<String>read(
                    salon + ".responses." + status + ".content['application/problem+json'].schema.$ref"))
                    .as(status).isEqualTo("#/components/schemas/Problem");
        }
        assertThat(document.<List<String>>read("$.components.schemas.Salon.required"))
                .containsExactlyInAnyOrder("slug", "name", "brandColour");
        assertThat(document.<Map<String, Object>>read("$.components.schemas.Salon.properties").keySet())
                .as("nothing identifies the salon beyond what its customers see")
                .containsExactlyInAnyOrder("slug", "name", "brandColour");
        assertThat(document.<Map<String, Object>>read(salon))
                .as("the salon comes from the hostname, never from a parameter").doesNotContainKey("parameters");
    }

    @Test
    void everyOperationCanFailWithProblemDetailsThatCarryACode() {
        DocumentContext document = document();

        assertThat(document.<String>read(
                "$.paths['/api/v1/meta'].get.responses.default.content['application/problem+json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Problem");
        assertThat(document.<List<String>>read("$.components.schemas.Problem.required")).contains("code");
    }

    @Test
    void theDocumentDoesNotDependOnWhereItWasGenerated() {
        assertThat(document().<List<Map<String, Object>>>read("$.servers")).containsExactly(Map.of("url", "/"));
    }

    @Test
    void onlyTheApiIsDescribed() {
        assertThat(document().<Map<String, Object>>read("$.paths").keySet()).allMatch(path -> path.startsWith("/api/v1/"));
    }

    private DocumentContext document() {
        return JsonPath.parse(http().get("/v3/api-docs").body());
    }
}
