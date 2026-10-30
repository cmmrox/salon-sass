package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.SignIn;
import com.salonplatform.identity.dto.Tokens;

/**
 * Staff password sign-in and password change (docs/architecture/identity-and-access.md). A sign-in counts
 * against two limits, 10 attempts per identifier and 30 per address in fifteen minutes, before the password is
 * checked; an Owner, and anyone enrolled, continues to a second step.
 */
public interface StaffSignInService {

    /**
     * Signs a staff member in with an email or mobile and a password, or hands them the challenge of their second
     * step.
     *
     * @param address the client's address, counted against its own limit
     * @throws com.salonplatform.shared.web.ApplicationProblemException 429 {@code rate_limited} past a limit, and
     *     401 {@code invalid_credentials} for a wrong identifier or password
     */
    SignIn signIn(String identifier, String password, String deviceLabel, String address);

    /**
     * Changes the signed-in staff member's password, ends their other sessions and rotates the current one
     * (docs/architecture/identity-and-access.md).
     */
    Tokens changePassword(String oldPassword, String newPassword);
}
