package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.Enrollment;
import com.salonplatform.identity.dto.SignIn;
import com.salonplatform.identity.dto.Tokens;
import java.util.UUID;

/**
 * A staff member's second step (docs/architecture/identity-and-access.md): authenticator-app enrollment, offered
 * after a correct password or from a signed-in session, and verification of a code, each through a single-use
 * challenge. A code's time step is accepted once per account.
 */
public interface StaffMfaService {

    /**
     * A signed-in staff member without a second step asks to enroll one.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code conflict} when they have one
     */
    SignIn voluntaryEnrollment();

    /** The authenticator secret of an enrollment challenge, shown once with its {@code otpauth} URI. */
    Enrollment enroll(UUID challengeId, String token);

    /**
     * Verifies a code against the account's secret, or the secret being enrolled, and signs the staff member in.
     * Confirming an enrollment ends the account's other sessions.
     */
    Tokens verify(UUID challengeId, String token, String code);
}
