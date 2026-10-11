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
