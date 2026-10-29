package com.salonplatform.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CodeRequest(
        @NotBlank @Size(max = 32) String mobile,
        @NotBlank @Size(max = 16) String channel,
        @NotBlank @Size(max = 160) String deviceLabel) {}
