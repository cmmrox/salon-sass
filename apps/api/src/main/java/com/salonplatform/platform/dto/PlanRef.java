package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"key", "name"})
public record PlanRef(String key, String name) {}
