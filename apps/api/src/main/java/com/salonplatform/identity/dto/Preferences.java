package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"sms", "whatsapp", "email"})
public record Preferences(boolean sms, boolean whatsapp, boolean email) {}
