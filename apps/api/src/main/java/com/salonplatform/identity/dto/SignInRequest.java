package com.salonplatform.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignInRequest(
        @NotBlank @Size(max = 254) String identifier,
        @NotBlank @Size(max = 512) String password,
        @NotBlank @Size(max = 160) String deviceLabel) {}
