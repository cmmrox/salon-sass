package com.salonplatform.shared.api;

import java.time.Instant;

import com.salonplatform.shared.application.ServiceMeta;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(name = "Meta", description = "What the running service says about itself.")
public record MetaResponse(
        @Schema(description = "The service's name.", example = "salon-api", requiredMode = RequiredMode.REQUIRED)
        String service,
        @Schema(description = "The version the build was given.", example = "0.1.0", requiredMode = RequiredMode.REQUIRED)
        String version,
        @Schema(description = "The commit the build was made from, or `unknown` when the build had no repository.",
                example = "e852c61f0c0e4d7a9b1f3c5d7e9a0b2c4d6e8f01", requiredMode = RequiredMode.REQUIRED)
        String commit,
        @Schema(description = "The server's time: an ISO-8601 instant in UTC.", example = "2026-10-12T02:17:00.123Z",
                requiredMode = RequiredMode.REQUIRED)
        Instant time) {

    static MetaResponse from(ServiceMeta meta) {
        return new MetaResponse(meta.service(), meta.version(), meta.commit(), meta.time());
    }
}
