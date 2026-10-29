package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@Schema(requiredProperties = {"id", "kind", "role", "permissions", "mfaEnabled"})
public record OperatorPrincipal(UUID id, String kind, String role, List<String> permissions, boolean mfaEnabled) {}
