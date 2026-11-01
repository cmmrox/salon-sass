package com.salonplatform.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The committed OpenAPI document ({@code docs/api/openapi.json}) as the judge of a real response
 * (docs/engineering/backend.md, "Tests", Contract; decision 068): the response's status must be documented
 * for its operation by itself, not only by the catch-all {@code default}, its content type must be the
 * documented one, and its body must be valid against the documented schema under JSON Schema 2020-12 (the
 * schema language of OpenAPI 3.1), with formats asserted. So a field that comes back null where the schema
 * does not allow it, a status the document does not name, or a missing required field fails the test that
 * made the call.
 *
 * <p>The document read is the committed one, not what the controllers generate now: {@code OpenApiDocumentTest}
 * holds the two equal, and clients are generated from the committed one.
 */
public final class OpenApiContract {

    private static final Path DOCUMENT =
            Path.of(System.getProperty("contracts.document", "../../docs/api/openapi.json"));

    /** The document's own address inside the registry; never fetched. */
    private static final String ADDRESS = "https://salon.invalid/openapi.json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static volatile OpenApiContract committed;

    private final JsonNode document;

    private final SchemaRegistry registry;

    private OpenApiContract(String source) {
        this.document = JSON.readTree(source);
        this.registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12, builder -> builder.schemas(Map.of(ADDRESS, source)));
    }

    /** The committed document, read once per test run. */
    public static OpenApiContract committed() {
        OpenApiContract contract = committed;
        if (contract == null) {
            synchronized (OpenApiContract.class) {
                if (committed == null) {
                    try {
                        committed = new OpenApiContract(Files.readString(DOCUMENT));
                    } catch (IOException ex) {
                        throw new UncheckedIOException("docs/api/openapi.json could not be read", ex);
                    }
                }
                contract = committed;
            }
        }
        return contract;
    }

    /** A contract over another document, for the tests that prove this one fails. */
    static OpenApiContract of(String document) {
        return new OpenApiContract(document);
    }

    /**
     * Fails unless the response is one the document promises for this operation.
     *
     * @param method the HTTP method, any case
     * @param path the operation's path template as the document names it ({@code /api/v1/salon/profile/draft})
     * @param response what the API answered
     */
    public void assertMatches(String method, String path, Http.Exchange response) {
        assertThat(violations(method, path, response.status(), response.contentType(), response.body()))
                .as("%s %s answered %d %s", method, path, response.status(), response.body())
                .isEmpty();
    }

    /** What is wrong with a response against the document, one line each; empty when nothing is. */
    List<String> violations(String method, String path, int status, String contentType, String body) {
        String verb = method.toLowerCase(Locale.ROOT);
        JsonNode operation = document.path("paths").path(path).path(verb);
        if (operation.isMissingNode()) {
            return List.of("the document has no operation " + method + " " + path);
        }
        JsonNode documented = operation.path("responses").path(Integer.toString(status));
        if (documented.isMissingNode()) {
            return List.of("status " + status + " is not documented for " + method + " " + path);
        }
        JsonNode content = documented.path("content");
        if (content.isMissingNode() || content.isEmpty()) {
            return body == null || body.isEmpty()
                    ? List.of()
                    : List.of("status " + status + " is documented with no body, and one was answered");
        }
        String mediaType = contentType == null ? "" : contentType.split(";")[0].trim();
        if (content.path(mediaType).isMissingNode()) {
            return List.of("content type '" + mediaType + "' is not documented for status " + status);
        }
        String pointer = "#/paths/" + escape(path) + "/" + verb + "/responses/" + status + "/content/"
                + escape(mediaType) + "/schema";
        Schema schema = registry.getSchema(SchemaLocation.of(ADDRESS + pointer));
        JsonNode value;
        try {
            value = JSON.readTree(body);
        } catch (RuntimeException ex) {
            return List.of("the body is not JSON");
        }
        return schema
                .validate(value, context -> context.executionConfig(config -> config.formatAssertionsEnabled(true)))
                .stream()
                .map(Error::toString)
                .toList();
    }

    /** A JSON pointer reference token: {@code ~} and {@code /} escaped. */
    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
