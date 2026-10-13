package com.salonplatform.identity;

import com.salonplatform.identity.application.StaffAccess;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/**
 * Public access contract. Business modules enforce the returned Professional scope on their data.
 */
@Service
public class IdentityFacade {
    public record Principal(
            UUID id,
            String kind,
            String role,
            Set<String> permissions,
            String scope,
            UUID staffId,
            boolean mfaEnabled) {}

    private final StaffAccess access;

    public IdentityFacade(StaffAccess access) {
        this.access = access;
    }

    public Principal current() {
        return access.me();
    }

    public Principal require(String permission) {
        Principal p = current();
        if (!p.permissions().contains(permission))
            throw new com.salonplatform.shared.api.ApplicationProblemException(
                    403, com.salonplatform.shared.api.ErrorCode.PERMISSION_DENIED);
        return p;
    }
}
