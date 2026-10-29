package com.salonplatform.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record VerifyRequest(
        @NotNull UUID challengeId,
        @NotBlank @Size(max = 256) String challengeToken,
        @NotBlank @Size(max = 32) String code) {}
