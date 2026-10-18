package com.salonplatform.identity;

import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.domain.PasswordPolicy;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.FieldProblem;
import com.salonplatform.shared.infrastructure.PlatformDatabase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Identity's narrow public provisioning facade (docs/architecture/operator-console.md): the identity
 * rules platform needs for an operator or a salon's contact, and the one bootstrap of a new salon's
 * first Owner. The Owner is inserted by identity's own {@code identity_bootstrap_owner} function on
 * the platform connection, inside the platform transaction that creates the salon; platform never
 * writes identity's tables, and this facade never commits on its own.
 */
@Service
public class IdentityProvisioning {

    /** A first Owner whose input passed identity's rules; the password is already hashed. */
    public static final class PreparedOwner {
        private final String name;
        private final String email;
        private final String passwordHash;

        private PreparedOwner(String name, String email, String passwordHash) {
            this.name = name;
            this.email = email;
            this.passwordHash = passwordHash;
        }

        public String email() {
            return email;
        }

        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return "PreparedOwner[redacted]";
        }
    }

    private final IdentityCrypto crypto;
    private final ObjectProvider<PlatformDatabase> platform;

    public IdentityProvisioning(IdentityCrypto crypto, ObjectProvider<PlatformDatabase> platform) {
        this.crypto = crypto;
        this.platform = platform;
    }

    /** Identity's password rule: 15 to 128 code points and not a listed common password. */
    public boolean acceptablePassword(String password) {
        return PasswordPolicy.valid(password);
    }

    /**
     * Identity's email normalization: trimmed, lower case, a plausible address of at most 254
     * characters. Blank is null.
     *
     * @throws IllegalArgumentException when it is not an acceptable address
     */
    public String normalizedEmail(String value) {
        return Identifiers.email(value);
    }

    /**
     * Identity's Sri Lankan mobile normalization to {@code +947XXXXXXXX}. Blank is null.
     *
     * @throws IllegalArgumentException when it is not such a number
     */
    public String normalizedMobile(String value) {
        return Identifiers.mobile(value);
    }

    /**
     * Validates a first Owner with identity's rules and hashes the password, before any
     * transaction opens. Field names are prefixed (for example {@code owner.email}).
     *
     * @throws ApplicationProblemException 400 {@code bad_request} naming every failing field
     */
    public PreparedOwner prepareOwner(String prefix, String name, String email, String password) {
        List<FieldProblem> errors = new ArrayList<>();
        String trimmedName = name == null ? null : name.strip();
        if (trimmedName == null || trimmedName.isEmpty() || trimmedName.codePointCount(0, trimmedName.length()) > 120
                || trimmedName.codePoints().anyMatch(Character::isISOControl))
            errors.add(new FieldProblem(prefix + "name", "must be plain text between 1 and 120 characters"));
        String normalizedEmail = null;
        try {
            normalizedEmail = email != null && email.length() <= 254 ? Identifiers.email(email) : null;
        } catch (IllegalArgumentException ex) {
            normalizedEmail = null;
        }
        if (normalizedEmail == null) errors.add(new FieldProblem(prefix + "email", "must be a valid email address"));
        if (!PasswordPolicy.valid(password))
            errors.add(new FieldProblem(prefix + "password",
                    "must contain 15 to 128 characters and must not be a common password"));
        if (!errors.isEmpty()) throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        return new PreparedOwner(trimmedName, normalizedEmail, crypto.encodePassword(password));
    }

    /**
     * Inserts the prepared Owner of a salon created in the current platform transaction: active,
     * role Owner, no MFA secret and no session. The database refuses any other salon and a second
     * bootstrap.
     *
     * @return the new Owner's staff id
     * @throws IllegalStateException outside a platform transaction
     */
    public UUID bootstrapOwner(UUID tenantId, PreparedOwner owner, Instant at) {
        PlatformDatabase database = platform.getObject();
        database.requireTransaction();
        UUID id = UUID.randomUUID();
        database.jdbc().queryForObject("SELECT identity_bootstrap_owner(?, ?, ?, ?, ?, ?)::text", String.class,
                tenantId, id, owner.name, owner.email, owner.passwordHash, Timestamp.from(at));
        return id;
    }
}
