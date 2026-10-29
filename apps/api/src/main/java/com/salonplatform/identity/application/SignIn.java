package com.salonplatform.identity.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(requiredProperties = {"state"})
public record SignIn(String state, UUID challengeId, String challengeToken, Instant expiresAt, Tokens credentials) {}
