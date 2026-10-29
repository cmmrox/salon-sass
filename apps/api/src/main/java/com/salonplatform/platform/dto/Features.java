package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"customDomain", "dedicatedApp"})
public record Features(boolean customDomain, boolean dedicatedApp) {}
