package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.OperatorEnrollment;
import com.salonplatform.platform.dto.OperatorSignIn;
import com.salonplatform.platform.dto.OperatorTokens;
import java.util.UUID;

/**
 * Operator sign-in (docs/architecture/operator-console.md, "Operator authentication"): a password, then
 * a five-minute single-use MFA challenge, which every operator completes (enrolling after a correct
 * password on first sign-in) before any session exists. Password attempts are limited per email and per
 * trusted address, unknown emails included, and every refusal commits its counters and audit.
 */
public interface OperatorSignInService {

    /**
     * Verifies the password and issues a five-minute single-use MFA challenge: {@code enroll_mfa} for an
     * operator without a secret, {@code verify_mfa} otherwise. Never a session.
     */
    OperatorSignIn signIn(String email, String password, String label, String address);

    /**
     * The authenticator setup for an enrollment challenge only, after a correct password: the secret
     * and its {@code otpauth} URI, shown once to be copied. Repeated calls show the same pending secret.
     */
    OperatorEnrollment enroll(UUID challengeId, String token);

    /**
     * Verifies a six-digit code against the challenge once: a consumed step, an expired, consumed or
     * exhausted challenge, or a wrong code issues nothing, and each attempt counts even when it fails.
     * Enrollment saves the secret and revokes any earlier session.
     */
    OperatorTokens verify(UUID challengeId, String token, String code);
}
