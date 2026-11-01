package com.salonplatform.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.testing.IntegrationTest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What the generated OpenAPI document promises its clients, whatever else changes: a published
 * path, operation or field is not renamed or removed (docs/engineering/backend.md).
 */
class OpenApiShapeTest extends IntegrationTest {

    @Test
    void metaIsDescribedWithEveryFieldRequired() {
        DocumentContext document = document();

        assertThat(document.<String>read("$.paths['/api/v1/meta'].get.operationId"))
                .isEqualTo("getMeta");
        assertThat(document.<String>read(
                        "$.paths['/api/v1/meta'].get.responses.200.content['application/json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Meta");
        assertThat(document.<List<String>>read("$.components.schemas.Meta.required"))
                .containsExactlyInAnyOrder("service", "version", "commit", "time");
        assertThat(document.<String>read("$.components.schemas.Meta.properties.time.format"))
                .isEqualTo("date-time");
    }

    @Test
    void salonIsDescribedWithItsBrandAndItsNotFoundAnswer() {
        DocumentContext document = document();
        String salon = "$.paths['/api/v1/salon'].get";

        assertThat(document.<String>read(salon + ".operationId")).isEqualTo("getSalon");
        assertThat(document.<String>read(salon + ".responses.200.content['application/json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Salon");
        for (String status : new String[] {"403", "404", "503"}) {
            assertThat(document.<String>read(
                            salon + ".responses." + status + ".content['application/problem+json'].schema.$ref"))
                    .as(status)
                    .isEqualTo("#/components/schemas/Problem");
        }
        assertThat(document.<List<String>>read("$.components.schemas.Salon.required"))
                .containsExactlyInAnyOrder("slug", "name", "brandColour", "timeZone", "profile");
        assertThat(document.<Map<String, Object>>read("$.components.schemas.Salon.properties")
                        .keySet())
                .as("nothing identifies the salon beyond what its customers see: its brand and published profile"
                        + " (F04-S01)")
                .containsExactlyInAnyOrder("slug", "name", "brandColour", "timeZone", "profile");
        assertThat(document.<Map<String, Object>>read(salon))
                .as("the salon comes from the hostname, never from a parameter")
                .doesNotContainKey("parameters");
    }

    @Test
    void aSuspendedSalonIsDescribedAsForbiddenWithItsCode() {
        DocumentContext document = document();

        assertThat(document.<String>read("$.paths['/api/v1/salon'].get.responses.403.description"))
                .contains("`salon_suspended`");
        assertThat(document.<List<String>>read("$.components.schemas.Problem.properties.code.enum"))
                .contains("salon_suspended");
    }

    /** The codes are listed in one place, {@link ErrorCode}, and the contract lists exactly those. */
    @Test
    void theContractListsEveryErrorCodeAndNoOther() {
        assertThat(document().<List<String>>read("$.components.schemas.Problem.properties.code.enum"))
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList())
                .contains(
                        "not_found",
                        "bad_request",
                        "method_not_allowed",
                        "not_acceptable",
                        "unsupported_media_type",
                        "service_unavailable",
                        "request_rejected",
                        "internal_error");
    }

    @Test
    void everyOperationCanFailWithProblemDetailsThatCarryACode() {
        DocumentContext document = document();

        assertThat(
                        document.<String>read(
                                "$.paths['/api/v1/meta'].get.responses.default.content['application/problem+json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Problem");
        assertThat(document.<List<String>>read("$.components.schemas.Problem.required"))
                .contains("code");
    }

    @Test
    void theDocumentDoesNotDependOnWhereItWasGenerated() {
        assertThat(document().<List<Map<String, Object>>>read("$.servers")).containsExactly(Map.of("url", "/"));
    }

    @Test
    void onlyTheApiIsDescribed() {
        assertThat(document().<Map<String, Object>>read("$.paths").keySet())
                .allMatch(path -> path.startsWith("/api/v1/"));
    }

    /**
     * OpenAPI 3.1 is JSON Schema 2020-12: a {@code $ref} beside {@code type: [object, null]} applies
     * both, so null fails the referenced schema, and an enum that omits null refuses null whatever
     * its type says (BUG-F03-001). A field the API may answer with null must accept null.
     */
    @Test
    void everyNullableFieldAcceptsNullUnderJsonSchema() {
        Map<String, Object> document =
                JsonPath.parse(http().get("/v3/api-docs").body()).json();
        List<String> wrong = new java.util.ArrayList<>();
        walk(document, "#", (path, node) -> {
            if (node.containsKey("$ref") && node.containsKey("type")) {
                wrong.add(path + ": $ref beside type");
            }
            if (node.get("type") instanceof List<?> types
                    && types.contains("null")
                    && node.get("enum") instanceof List<?> values
                    && !values.contains(null)) {
                wrong.add(path + ": nullable enum without null");
            }
        });
        assertThat(wrong).isEmpty();

        Map<String, List<String>> nullable = Map.of(
                "OperatorSignIn", List.of("credentials"),
                "SalonDetail", List.of("plan"),
                "SalonSummary", List.of("plan"),
                "SubscriptionView", List.of("plan", "currentPeriod"),
                "Entitlements", List.of("plan", "limits", "features", "subscription"),
                "SubscriptionState", List.of("notice"),
                "SupportSessionView", List.of("endedBy"),
                "SalonSupportSession", List.of("endedBy"));
        DocumentContext context = document();
        nullable.forEach((schema, fields) -> fields.forEach(field -> {
            Map<String, Object> property = context.read("$.components.schemas." + schema + ".properties." + field);
            assertThat(acceptsNull(property))
                    .as("%s.%s accepts null: %s", schema, field, property)
                    .isTrue();
            assertThat(context.<List<String>>read("$.components.schemas." + schema + ".required"))
                    .as("%s.%s is always present", schema, field)
                    .contains(field);
        }));
        assertThat(context.<String>read("$.components.schemas.SalonDetail.properties.plan.oneOf[0].$ref"))
                .as("a nullable reference still names its schema")
                .isEqualTo("#/components/schemas/PlanSummary");
    }

    /** The support routes answer 201 for what they create and 204 for every ending (F03-S04). */
    @Test
    void supportRoutesDocumentTheStatusesTheyAnswer() {
        DocumentContext document = document();
        Map<String, String> expected = Map.of(
                "$.paths['/api/v1/operator/salons/{id}/support-sessions'].post", "201",
                "$.paths['/api/v1/operator/support-sessions/{id}/entry-codes'].post", "201",
                "$.paths['/api/v1/operator/support-sessions/{id}'].delete", "204",
                "$.paths['/api/v1/salon/support-sessions/{id}'].delete", "204",
                "$.paths['/api/v1/support/session'].delete", "204");
        expected.forEach((operation, status) -> assertThat(document.<Map<String, Object>>read(operation + ".responses")
                        .keySet())
                .as(operation)
                .containsExactlyInAnyOrder(status, "default"));
    }

    private static boolean acceptsNull(Map<String, Object> property) {
        for (String combiner : List.of("oneOf", "anyOf")) {
            if (property.get(combiner) instanceof List<?> members
                    && members.stream()
                            .anyMatch(member -> member instanceof Map<?, ?> m && "null".equals(m.get("type")))) {
                return !property.containsKey("$ref") && !property.containsKey("type");
            }
        }
        return property.get("type") instanceof List<?> types
                && types.contains("null")
                && !property.containsKey("$ref")
                && (!(property.get("enum") instanceof List<?> values) || values.contains(null));
    }

    // The OpenAPI document is read as untyped maps; walk visits its JSON objects only.
    @SuppressWarnings("unchecked")
    private static void walk(
            Object node, String path, java.util.function.BiConsumer<String, Map<String, Object>> visit) {
        if (node instanceof Map<?, ?> map) {
            visit.accept(path, (Map<String, Object>) map);
            map.forEach((key, value) -> walk(value, path + "/" + key, visit));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                walk(list.get(i), path + "/" + i, visit);
            }
        }
    }

    private DocumentContext document() {
        return JsonPath.parse(http().get("/v3/api-docs").body());
    }
}
