package com.salonplatform.platform.dto;

/** An account to create. The password is validated with identity's policy and hashed here. */
public record AccountInput(String email, String displayName, String role, String password, String totpSecret) {}
