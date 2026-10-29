package com.salonplatform.identity.application;

import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.domain.PasswordPolicy;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.identity.infrastructure.IdentityPlatformFunctions;
import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.identity.inprocess.PreparedOwner;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The first Owner is inserted by identity's own {@code identity_bootstrap_owner} function on the
 * platform connection, inside the platform transaction that creates the salon.
 */
@Service
class IdentityProvisioningService implements IdentityProvisioning {

    private final IdentityCrypto crypto;
    private final ObjectProvider<PlatformDatabase> platform;
    private final IdentityPlatformFunctions functions;

    IdentityProvisioningService(
            IdentityCrypto crypto, ObjectProvider<PlatformDatabase> platform, IdentityPlatformFunctions functions) {
        this.crypto = crypto;
        this.platform = platform;
        this.functions = functions;
    }

    @Override
    public boolean acceptablePassword(String password) {
        return PasswordPolicy.valid(password);
    }

    @Override
    public String normalizedEmail(String value) {
        return Identifiers.email(value);
    }

    @Override
    public String normalizedMobile(String value) {
        return Identifiers.mobile(value);
    }

    @Override
    public PreparedOwner prepareOwner(String prefix, String name, String email, String password) {
        List<FieldProblem> errors = new ArrayList<>();
        String trimmedName = name == null ? null : name.strip();
        if (trimmedName == null
                || trimmedName.isEmpty()
                || trimmedName.codePointCount(0, trimmedName.length()) > 120
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
            errors.add(new FieldProblem(
                    prefix + "password", "must contain 15 to 128 characters and must not be a common password"));
        if (!errors.isEmpty()) throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        return new ValidatedOwner(trimmedName, normalizedEmail, crypto.encodePassword(password));
    }

    @Override
    public UUID bootstrapOwner(UUID tenantId, PreparedOwner owner, Instant at) {
        if (!(owner instanceof ValidatedOwner validated)) {
            throw new IllegalArgumentException("Only identity's prepareOwner prepares an Owner for the bootstrap");
        }
        platform.getObject().requireTransaction();
        UUID id = UUID.randomUUID();
        functions.bootstrapOwner(tenantId, id, validated.name(), validated.email(), validated.passwordHash(), at);
        return id;
    }

    @Override
    public long activeStaffCount(UUID tenantId) {
        platform.getObject().requireTransaction();
        return functions.activeStaffCount(tenantId);
    }
}
