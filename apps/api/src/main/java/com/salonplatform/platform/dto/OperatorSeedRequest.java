package com.salonplatform.platform.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The local operator seed. {@code keepExisting} true creates only the accounts that do not exist yet and leaves
 * every existing account exactly as it is (the fixed local roster, decision 099); absent or false brings each
 * synthetic account to the given state.
 */
public record OperatorSeedRequest(@NotEmpty @Size(max = 20) List<AccountInput> accounts, Boolean keepExisting) {}
