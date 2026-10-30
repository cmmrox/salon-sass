package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What one pass of the export job did. */
@Schema(hidden = true)
public record ExportJobPass(Instant at, List<ExportPass> exports, List<UUID> failed) {}
