package com.salonplatform.identity;

import java.util.UUID;

/**
 * An active staff account of the current salon, as a Team page offers it for linking (F06-S01).
 *
 * @param id the account
 * @param label its email, else its mobile number, as the staff list shows it
 * @param role its role, such as {@code MANAGER}
 */
public record SignInAccount(UUID id, String label, String role) {}
