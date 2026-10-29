package com.salonplatform.identity.dto;

public record SeedAccount(
        String email, String mobile, String password, String role, String totpSecret, Boolean professionalGrant) {}
