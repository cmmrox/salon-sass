package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.shared.infrastructure.ModuleOwnership;
import com.salonplatform.testing.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every API operation is tagged with the module that owns its path, and each module's own document holds
 * exactly its paths ({@link ApiOwnership}). The rule is also shown documents that break it.
 */
class ApiOwnershipIntegrationTest extends IntegrationTest {

    private static final ModuleOwnership OWNERSHIP = ModuleOwnership.load();

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyOperationCarriesExactlyItsModulesTag() {
        Map<String, Object> document = document("/v3/api-docs");

        assertThat(paths(document)).as("the document has the API's paths").contains("/api/v1/meta", "/api/v1/salon");
        assertThat(ApiOwnership.operationsOutsideTheirModule(OWNERSHIP, document))
                .isEmpty();
    }

    /** Every module of the map, including one added later, has its document. */
    @ParameterizedTest
    @MethodSource("modules")
    void eachModulesDocumentHoldsExactlyTheModulesPaths(String module) {
        Set<String> owned = new TreeSet<>(paths(document("/v3/api-docs")));
        owned.removeIf(path -> !OWNERSHIP.ownerOfPath(path).orElseThrow().equals(module));

        Map<String, Object> own = document("/v3/api-docs/" + module);

        assertThat(owned).isNotEmpty();
        assertThat(paths(own)).containsExactlyInAnyOrderElementsOf(owned);
        assertThat(ApiOwnership.operationsOutsideTheirModule(OWNERSHIP, own)).isEmpty();
        assertThat(JSON.writeValueAsString(own))
                .as("every failure is described in the module's document too")
                .contains("\"Problem\"");
    }

    static Stream<String> modules() {
        return OWNERSHIP.modules().stream().map(ModuleOwnership.Module::name);
    }

    @Test
    void aModuleWithoutADocumentIsNotFound() {
        HttpResponse<String> response = http().get("/v3/api-docs/catalogue");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("not_found");
    }

    @Test
    void anUntaggedOperationIsFound() {
        assertThat(ApiOwnership.operationsOutsideTheirModule(
                        OWNERSHIP, fixture("/api/v1/identity/me", "get", List.of())))
                .containsExactly("GET /api/v1/identity/me carries 0 tags [], not exactly identity");
    }

    @Test
    void anOperationWithTwoTagsIsFound() {
        assertThat(ApiOwnership.operationsOutsideTheirModule(
                        OWNERSHIP, fixture("/api/v1/identity/me", "get", List.of("identity", "platform"))))
                .containsExactly("GET /api/v1/identity/me carries 2 tags [identity, platform], not exactly identity");
    }

    @Test
    void anOperationTaggedWithAnotherModuleIsFound() {
        assertThat(ApiOwnership.operationsOutsideTheirModule(
                        OWNERSHIP, fixture("/api/v1/salon/invoices/{id}", "get", List.of("branding"))))
                .containsExactly("GET /api/v1/salon/invoices/{id} is tagged branding but belongs to platform");
    }

    @Test
    void anOperationOnAPathNoModuleOwnsIsFound() {
        assertThat(ApiOwnership.operationsOutsideTheirModule(
                        OWNERSHIP, fixture("/api/v1/salons", "post", List.of("branding"))))
                .containsExactly("POST /api/v1/salons matches no path prefix in the map");
    }

    private Map<String, Object> document(String path) {
        HttpResponse<String> response = http().get(path);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return JSON.readValue(response.body(), new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked") // JSON objects read as maps have String keys.
    private static Set<String> paths(Map<String, Object> document) {
        return ((Map<String, Object>) document.get("paths")).keySet();
    }

    private static Map<String, Object> fixture(String path, String method, List<String> tags) {
        return Map.of("paths", Map.of(path, Map.of(method, Map.of("operationId", "fixture", "tags", tags))));
    }
}
