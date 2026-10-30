package com.salonplatform.identity.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SeedRequest(@NotEmpty @Size(max = 20) List<SeedAccount> accounts) {}
