package com.salonplatform.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordRequest(
        @NotBlank @Size(max = 512) String oldPassword,
        @NotBlank @Size(max = 512) String newPassword) {}
