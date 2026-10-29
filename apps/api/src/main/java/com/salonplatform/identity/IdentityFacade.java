package com.salonplatform.identity;

import com.salonplatform.identity.application.SessionAccess;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Public access contract. Business modules enforce the returned Professional scope on their data.
 */
@Service
public class IdentityFacade {
    @io.swagger.v3.oas.annotations.media.Schema(
            requiredProperties = {"id", "kind", "role", "permissions", "scope", "staffId", "mfaEnabled", "expiresAt"})
    public record Principal(
            UUID id,
            String kind,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            String role,

            Set<String> permissions,
            String scope,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    format = "uuid",
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            UUID staffId,

            boolean mfaEnabled,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    description = "When a support session (kind support) ends; null for every" + " other kind",
                    requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
            java.time.Instant expiresAt) {
        public Principal(
                UUID id,
                String kind,
                String role,
                Set<String> permissions,
                String scope,
                UUID staffId,
                boolean mfaEnabled) {
            this(id, kind, role, permissions, scope, staffId, mfaEnabled, null);
        }
    }

    private final SessionAccess access;

    public IdentityFacade(SessionAccess access) {
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
