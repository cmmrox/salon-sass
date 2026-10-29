package com.salonplatform.identity.application;

import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.domain.PasswordPolicy;
import com.salonplatform.identity.domain.StaffRole;

/** Staff input, normalized or refused field by field (docs/architecture/identity-and-access.md). */
final class StaffValidation {

    private StaffValidation() {}

    static void requireAcceptablePassword(String password, String field) {
        if (!PasswordPolicy.valid(password)) {
            throw IdentityProblems.invalidField(
                    field, "must contain 15 to 128 characters and must not be a common password");
        }
    }

    /** A device label is plain text the person chose: shown back to them, never trusted. */
    static void requireDeviceLabel(String label) {
        if (label == null
                || label.isBlank()
                || label.codePointCount(0, label.length()) > 80
                || label.codePoints().anyMatch(Character::isISOControl)) {
            throw IdentityProblems.invalidField("deviceLabel", "must be plain text between 1 and 80 characters");
        }
    }

    static StaffRole role(String value) {
        try {
            return StaffRole.valueOf(value);
        } catch (RuntimeException ex) {
            throw IdentityProblems.invalidField("role", "must be one of the five staff roles");
        }
    }

    static String email(String value) {
        try {
            return Identifiers.email(value);
        } catch (IllegalArgumentException ex) {
            throw IdentityProblems.invalidField("email", "must be a valid email address");
        }
    }

    static String mobile(String value) {
        try {
            return Identifiers.mobile(value);
        } catch (IllegalArgumentException ex) {
            throw IdentityProblems.invalidField("mobile", "must be a Sri Lankan mobile number");
        }
    }
}
