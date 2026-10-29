package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(requiredProperties = {"generatedAt", "profile", "devices"})
public record Export(Instant generatedAt, Profile profile, List<SessionView> devices) {}
