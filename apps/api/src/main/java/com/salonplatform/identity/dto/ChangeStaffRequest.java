package com.salonplatform.identity.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ChangeStaffRequest(@NotNull @Min(0) Long version, String role, Boolean professionalGrant) {}
