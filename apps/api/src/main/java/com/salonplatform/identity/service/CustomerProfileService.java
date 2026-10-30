package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.Export;
import com.salonplatform.identity.dto.Preferences;
import com.salonplatform.identity.dto.Profile;
import java.time.LocalDate;

/**
 * A signed-in customer's own profile and data rights (docs/architecture/identity-and-access.md); every privacy
 * effect shares the identity security transaction.
 */
public interface CustomerProfileService {

    /** A profile change, made against the version it was read at. */
    record Change(
            long version,
            String name,
            String email,
            LocalDate birthday,
            String preferredLanguage,
            Preferences preferences,
            boolean marketingConsent) {}

    /** The signed-in customer's profile. */
    Profile read();

    /**
     * Changes the signed-in customer's profile.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code conflict} when the profile
     *     changed since the version named
     */
    Profile update(Change change);

    /** Everything identity holds about the signed-in customer: the profile and the devices signed in. */
    Export export();

    /**
     * Deletes the signed-in customer's account, their sessions and their queued codes, and announces the erasure.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 {@code confirmation_required} unless
     *     confirmed
     */
    void delete(boolean confirm);
}
