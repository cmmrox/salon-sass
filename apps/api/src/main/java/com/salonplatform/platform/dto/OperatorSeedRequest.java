package com.salonplatform.platform.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OperatorSeedRequest(@NotEmpty @Size(max = 20) List<AccountInput> accounts) {}
