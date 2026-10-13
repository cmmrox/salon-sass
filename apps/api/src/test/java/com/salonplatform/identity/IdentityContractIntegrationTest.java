package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.application.StaffAccess;
import com.salonplatform.testing.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public statuses and nullable/omitted response fields match the generated contract. */
class IdentityContractIntegrationTest extends IntegrationTest {
    @Autowired JsonMapper json;

    @Test
    void successStatusesAndPrincipalNullabilityAreExplicit() {
        Map<String, Object> document = json.readValue(http().get("/v3/api-docs").body(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        for (String path :
                List.of(
                        "/api/v1/identity/staff/{id}",
                        "/api/v1/identity/sessions",
                        "/api/v1/identity/sessions/{id}")) {
            Map<String, Object> operation =
                    (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("delete");
            assertThat(((Map<String, Object>) operation.get("responses")).keySet())
                    .contains("204")
                    .doesNotContain("200");
        }
        Map<String, Object> create =
                (Map<String, Object>)
                        ((Map<String, Object>) paths.get("/api/v1/identity/staff")).get("post");
        assertThat(((Map<String, Object>) create.get("responses")).keySet())
                .contains("201")
                .doesNotContain("200");
        Map<String, Object> schemas =
                (Map<String, Object>)
                        ((Map<String, Object>) document.get("components")).get("schemas");
        Map<String, Object> principal = (Map<String, Object>) schemas.get("Principal");
        Map<String, Object> properties = (Map<String, Object>) principal.get("properties");
        for (String field : List.of("role", "staffId"))
            assertThat((List<String>) ((Map<String, Object>) properties.get(field)).get("type"))
                    .containsExactlyInAnyOrder("string", "null");
        assertThat((List<String>) principal.get("required"))
                .contains("id", "kind", "role", "staffId", "permissions", "scope", "mfaEnabled");
    }

    @Test
    void customerAndStaffCodeVerificationRequestsHaveDistinctExactSchemas() {
        Map<String, Object> document = json.readValue(http().get("/v3/api-docs").body(), Map.class);
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        Map<String, Object> schemas =
                (Map<String, Object>)
                        ((Map<String, Object>) document.get("components")).get("schemas");
        for (String path :
                List.of(
                        "/api/v1/identity/customers/sign-in",
                        "/api/v1/identity/staff/mfa/verify")) {
            Map<String, Object> operation =
                    (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
            Map<String, Object> content =
                    (Map<String, Object>)
                            ((Map<String, Object>) operation.get("requestBody")).get("content");
            Map<String, Object> schema =
                    (Map<String, Object>)
                            ((Map<String, Object>) content.get("application/json")).get("schema");
            String ref = (String) schema.get("$ref");
            Map<String, Object> request =
                    (Map<String, Object>) schemas.get(ref.substring(ref.lastIndexOf('/') + 1));
            List<String> expected =
                    path.contains("customers")
                            ? List.of("challengeId", "code")
                            : List.of("challengeId", "challengeToken", "code");
            assertThat(((Map<String, Object>) request.get("properties")).keySet())
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat((List<String>) request.get("required"))
                    .containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @Test
    void profileContractRejectsSelectorsAndDeclaresNullableOptionalDataAndDeletionStatus() {
        Map<String, Object> document = json.readValue(http().get("/v3/api-docs").body(), Map.class);
        Map<String, Object> schemas =
                (Map<String, Object>)
                        ((Map<String, Object>) document.get("components")).get("schemas");
        Map<String, Object> profile = (Map<String, Object>) schemas.get("Profile");
        Map<String, Object> properties = (Map<String, Object>) profile.get("properties");
        assertThat(properties.keySet())
                .containsExactlyInAnyOrder(
                        "id",
                        "version",
                        "name",
                        "mobile",
                        "email",
                        "birthday",
                        "preferredLanguage",
                        "notificationPreferences",
                        "marketingConsent");
        for (String field : List.of("email", "birthday"))
            assertThat((List<String>) ((Map<String, Object>) properties.get(field)).get("type"))
                    .containsExactlyInAnyOrder("string", "null");
        Map<String, Object> update =
                (Map<String, Object>) schemas.get("CustomerProfileUpdateRequest");
        assertThat((Map<String, Object>) update.get("properties"))
                .containsOnlyKeys(
                        "version",
                        "name",
                        "email",
                        "birthday",
                        "preferredLanguage",
                        "notificationPreferences",
                        "marketingConsent");
        Map<String, Object> mutable = (Map<String, Object>) update.get("properties");
        for (String field : List.of("email", "birthday"))
            assertThat((List<String>) ((Map<String, Object>) mutable.get(field)).get("type"))
                    .containsExactlyInAnyOrder("string", "null");
        assertThat(update.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) update.get("required"))
                .containsExactlyInAnyOrder(
                        "version",
                        "name",
                        "preferredLanguage",
                        "notificationPreferences",
                        "marketingConsent");
        Map<String, Object> paths = (Map<String, Object>) document.get("paths");
        var delete =
                (Map<String, Object>)
                        ((Map<String, Object>) paths.get("/api/v1/identity/customers/me"))
                                .get("delete");
        assertThat(((Map<String, Object>) delete.get("responses")).keySet())
                .contains("204")
                .doesNotContain("200");
    }

    @Test
    void optionalSignInFieldsAreOmittedAndCustomerPrincipalFieldsAreNullable() {
        StaffAccess.SignIn challenge =
                new StaffAccess.SignIn(
                        "enroll_mfa",
                        UUID.randomUUID(),
                        UUID.randomUUID().toString(),
                        Instant.now(),
                        null);
        Map<String, Object> wire = json.readValue(json.writeValueAsString(challenge), Map.class);
        assertThat(wire.containsKey("credentials")).isFalse();
        var customer =
                new IdentityFacade.Principal(
                        UUID.randomUUID(),
                        "customer",
                        null,
                        Set.of("session.manage"),
                        "own",
                        null,
                        false);
        Map<String, Object> principal =
                json.readValue(json.writeValueAsString(customer), Map.class);
        assertThat(principal.containsKey("role")).isTrue();
        assertThat(principal.get("role")).isNull();
        assertThat(principal.containsKey("staffId")).isTrue();
        assertThat(principal.get("staffId")).isNull();
    }
}
