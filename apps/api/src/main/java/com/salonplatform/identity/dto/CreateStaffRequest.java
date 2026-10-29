package com.salonplatform.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStaffRequest(
        @Size(max = 254) String email,
        @Size(max = 32) String mobile,
        @NotBlank @Size(max = 512) String password,
        @NotBlank String role,
        Boolean professionalGrant) {}
