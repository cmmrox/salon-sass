package com.salonplatform.identity.domain;

/** What a second-step challenge is for, as {@code identity_mfa_challenge.purpose} holds it. */
public final class MfaPurpose {

    /** Enrolling an authenticator: an Owner without one, or anyone who asks. */
    public static final String ENROLL = "enroll_mfa";

    /** Verifying a code from the enrolled authenticator. */
    public static final String VERIFY = "verify_mfa";

    private MfaPurpose() {}
}
