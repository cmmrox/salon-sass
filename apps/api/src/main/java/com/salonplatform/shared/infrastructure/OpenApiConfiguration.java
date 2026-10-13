package com.salonplatform.shared.infrastructure;

import java.util.Arrays;
import java.util.List;

import com.salonplatform.shared.api.ErrorCode;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE;

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
                .info(new Info().title(service).version("v1")
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
            openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                if (operation.getResponses().getDefault() == null) {
                    operation.getResponses().setDefault(new ApiResponse()
                            .description("Any error: problem details with a stable `code`.")
                            .content(new Content().addMediaType(APPLICATION_PROBLEM_JSON_VALUE, new MediaType()
                                    .schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_SCHEMA)))));
                }
            }));
        };
    }

    private static Schema<?> problemSchema() {
        return new ObjectSchema()
                .description("An error, as problem details (RFC 9457) with a stable machine-readable `code`.")
                .addProperty("type", new StringSchema().description("A URI identifying the kind of problem."))
                .addProperty("title", new StringSchema().description("A short summary of the status."))
                .addProperty("status", new IntegerSchema().description("The HTTP status."))
                .addProperty("detail", new StringSchema().description("A sentence for a person. Do not branch on it."))
                .addProperty("instance", new StringSchema().description("The path that was requested."))
                .addProperty("code", new StringSchema().description("The stable reason. Branch on this.")
                        ._enum(Arrays.stream(ErrorCode.values()).map(ErrorCode::code).toList()))
                .addProperty("traceId", new StringSchema()
                        .description("The request's trace id: the `trace.id` of its log lines and of its trace.")
                        .pattern("^[0-9a-f]{32}$").example("4bf92f3577b34da6a3ce929d0e0e4736"))
                .addProperty("errors", new ArraySchema()
                        .description("For a request body that failed validation (`bad_request`): each field that "
                                + "failed, when the failure has a field. Never the value that was refused.")
                        .items(new ObjectSchema()
                                .addProperty("field", new StringSchema()
                                        .description("The field, as the request names it; a dotted path when nested."))
                                .addProperty("detail", new StringSchema()
                                        .description("What is wrong with it, for a person."))
                                .required(List.of("field", "detail"))))
                .required(List.of("title", "status", "code", "traceId"));
    }
}
