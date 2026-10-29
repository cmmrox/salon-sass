package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(hidden = true)
public record DeletionJobPass(Instant at, List<SalonPass> salons, List<UUID> waiting, List<UUID> failed) {}
