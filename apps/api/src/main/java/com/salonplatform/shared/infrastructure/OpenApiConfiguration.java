package com.salonplatform.shared.infrastructure;

import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

import com.salonplatform.shared.api.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shapes the OpenAPI document generated from the controllers. The document is committed at
 * {@code docs/api/openapi.json}, so nothing in it may depend on where or when it was generated.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
class OpenApiConfiguration {

    static final String PROBLEM_SCHEMA = "Problem";

    @Bean
    OpenAPI openApi(@Value("${spring.application.name}") String service) {
        return new OpenAPI()
                .info(new Info()
                        .title(service)
                        .version("v1")
                        .description("The platform's REST API. Every error is problem details with a stable `code`."))
                .servers(List.of(new Server().url("/")));
    }

    /** Every operation can fail, and every failure has the same shape. */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_SCHEMA, problemSchema());
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths()
                    .values()
                    .forEach(path -> path.readOperations().forEach(operation -> {
                        if (operation.getResponses().get(ApiResponses.DEFAULT) == null) {
                            operation
                                    .getResponses()
                                    .addApiResponse(
                                            ApiResponses.DEFAULT,
                                            new ApiResponse()
                                                    .description("Any error: problem details with a stable `code`.")
                                                    .content(new Content()
                                                            .addMediaType(
                                                                    APPLICATION_PROBLEM_JSON_VALUE,
                                                                    new MediaType()
                                                                            .schema(new Schema<>()
                                                                                    .$ref("#/components/schemas/"
                                                                                            + PROBLEM_SCHEMA)))));
                        }
                    }));
        };
    }

    /**
     * OpenAPI 3.1 is JSON Schema 2020-12, where every keyword applies. The generator writes a
     * nullable reference as {@code $ref} beside {@code type: [object, null]}, so null still fails the
     * referenced schema, and a nullable enum without null in its values, so null fails the enum
     * (BUG-F03-001, the class of BUG-F02-001). Rewrite both so that a client generated from the
     * document accepts the nulls the API answers: {@code oneOf: [{$ref}, {type: null}]} and
     * {@code null} among the enum values.
     */
    @Bean
    OpenApiCustomizer nullableSchemas() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            Map<Schema<?>, Boolean> seen = new IdentityHashMap<>();
            openApi.getComponents().getSchemas().values().forEach(schema -> nullable(schema, seen));
        };
    }

    // swagger-models types properties and allOf/oneOf/anyOf with the raw Schema, and a Schema<?>'s
    // enum can be replaced only through an unchecked cast.
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void nullable(Schema<?> schema, Map<Schema<?>, Boolean> seen) {
        if (schema == null || seen.put(schema, Boolean.TRUE) != null) {
            return;
        }
        if (schema.getProperties() != null) {
            Map<String, Schema> rewritten = new LinkedHashMap<>();
            schema.getProperties().forEach((name, property) -> rewritten.put(name, nullableReference(property)));
            schema.setProperties(rewritten);
            rewritten.values().forEach(property -> nullable(property, seen));
        }
        if (schema.getItems() != null) {
            schema.setItems(nullableReference(schema.getItems()));
            nullable(schema.getItems(), seen);
        }
        if (schema.getAdditionalProperties() instanceof Schema<?> additional) {
            schema.setAdditionalProperties(nullableReference(additional));
            nullable(additional, seen);
        }
        for (List<Schema> members : Arrays.asList(schema.getAllOf(), schema.getOneOf(), schema.getAnyOf())) {
            if (members != null) {
                members.forEach(member -> nullable(member, seen));
            }
        }
        Set<String> types = schema.getTypes();
        List<?> values = schema.getEnum();
        if (types != null && types.contains("null") && values != null && !values.contains(null)) {
            List<Object> withNull = new ArrayList<>(values);
            withNull.add(null);
            ((Schema<Object>) schema).setEnum(withNull);
        }
    }

    private static Schema<?> nullableReference(Schema<?> property) {
        Set<String> types = property.getTypes();
        if (property.get$ref() == null || types == null || !types.contains("null")) {
            return property;
        }
        Schema<Object> reference = new Schema<>();
        reference.specVersion(SpecVersion.V31);
        reference.$ref(property.get$ref());
        Schema<Object> nothing = new Schema<>();
        nothing.specVersion(SpecVersion.V31);
        nothing.types(Set.of("null"));
        Schema<Object> referenceOrNothing = new Schema<>();
        referenceOrNothing.specVersion(SpecVersion.V31);
        referenceOrNothing.description(property.getDescription());
        referenceOrNothing.oneOf(List.of(reference, nothing));
        return referenceOrNothing;
    }

    private static Schema<?> problemSchema() {
        StringSchema code = new StringSchema();
        code.description("The stable reason. Branch on this.");
        code._enum(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList());

        ObjectSchema fieldProblem = new ObjectSchema();
        fieldProblem.addProperty(
                "field",
                new StringSchema().description("The field, as the request names it; a dotted path when nested."));
        fieldProblem.addProperty("detail", new StringSchema().description("What is wrong with it, for a person."));
        fieldProblem.required(List.of("field", "detail"));

        ArraySchema fieldProblems = new ArraySchema();
        fieldProblems.description("For a request body that failed validation (`bad_request`): each field that "
                + "failed, when the failure has a field. Never the value that was refused.");
        fieldProblems.items(fieldProblem);

        ObjectSchema problem = new ObjectSchema();
        problem.description("An error, as problem details (RFC 9457) with a stable machine-readable `code`.")
                .addProperty("type", new StringSchema().description("A URI identifying the kind of problem."))
                .addProperty("title", new StringSchema().description("A short summary of the status."))
                .addProperty("status", new IntegerSchema().description("The HTTP status."))
                .addProperty("detail", new StringSchema().description("A sentence for a person. Do not branch on it."))
                .addProperty("instance", new StringSchema().description("The path that was requested."))
                .addProperty("code", code)
                .addProperty(
                        "traceId",
                        new StringSchema()
                                .description(
                                        "The request's trace id: the `trace.id` of its log lines and of its trace.")
                                .pattern("^[0-9a-f]{32}$")
                                .example("4bf92f3577b34da6a3ce929d0e0e4736"))
                .addProperty("errors", fieldProblems);
        problem.required(List.of("title", "status", "code", "traceId"));
        return problem;
    }
}
