package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(requiredProperties = {"id", "displayName"})
public record SupportOperatorRef(UUID id, String displayName) {}
