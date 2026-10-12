package com.salonplatform.contract;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.salonplatform.testing.IntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Generates the OpenAPI document from the controllers and keeps the committed copy honest.
 *
 * <p>The mode is the {@code contracts.mode} system property:
 * <ul>
 * <li>{@code check}, the default: the build fails when {@code docs/api/openapi.json} differs from
 * what the controllers generate.
 * <li>{@code export}: only writes {@code target/openapi.json}. {@code pnpm contracts:check} uses
 * it and does the comparing itself.
 * <li>{@code update}: rewrites {@code docs/api/openapi.json}. {@code pnpm contracts:generate}
 * uses it. The committed document is never edited by hand.
 * </ul>
 *
 * <p>This class only generates and compares. What the document must always say is asserted in
 * {@link OpenApiShapeTest}, so that generating never fails because the contract changed.
 */
class OpenApiDocumentTest extends IntegrationTest {

    private static final Path GENERATED = Path.of("target", "openapi.json");

    private static final Path COMMITTED = Path.of(System.getProperty("contracts.document", "../../docs/api/openapi.json"));

    @Test
    void theCommittedDocumentIsWhatTheControllersGenerate() throws IOException {
        HttpResponse<String> response = http().get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        String generated = canonical(response.body());

        Files.createDirectories(GENERATED.toAbsolutePath().getParent());
        Files.writeString(GENERATED, generated, StandardCharsets.UTF_8);

        switch (System.getProperty("contracts.mode", "check")) {
            case "export" -> { }
            case "update" -> {
                Files.createDirectories(COMMITTED.toAbsolutePath().getParent());
                Files.writeString(COMMITTED, generated, StandardCharsets.UTF_8);
            }
            default -> {
                assertThat(COMMITTED).as("docs/api/openapi.json is missing. Run `pnpm contracts:generate`.").exists();
                assertThat(Files.readString(COMMITTED, StandardCharsets.UTF_8))
                        .as("docs/api/openapi.json is stale: it is not what the controllers generate. "
                                + "Run `pnpm contracts:generate` and commit the result.")
                        .isEqualTo(generated);
            }
        }
    }

    /** Keys sorted, two-space indentation, a final newline: the same bytes on every machine. */
    private static String canonical(String json) {
        JsonMapper mapper = JsonMapper.builder().build();
        Object sorted = sorted(mapper.readValue(json, Object.class));
        Separators separators = Separators.createDefaultInstance()
                .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                .withObjectEmptySeparator("")
                .withArrayEmptySeparator("");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter(separators)
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return mapper.writer().with(printer).writeValueAsString(sorted) + "\n";
    }

    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> result = new TreeMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), sorted(value)));
            return result;
        }
        if (node instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }
}
