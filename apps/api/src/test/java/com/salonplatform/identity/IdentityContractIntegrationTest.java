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
