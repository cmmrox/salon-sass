package com.salonplatform.identity.application;

import com.salonplatform.identity.infrastructure.IdentityPlatformFunctions;
import com.salonplatform.identity.inprocess.IdentityLifecycle;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
class IdentityLifecycleService implements IdentityLifecycle {

    private final ObjectProvider<PlatformDatabase> platform;
    private final IdentityPlatformFunctions functions;

    IdentityLifecycleService(ObjectProvider<PlatformDatabase> platform, IdentityPlatformFunctions functions) {
        this.platform = platform;
        this.functions = functions;
    }

    @Override
    public int revokeClosedSalonSessions(UUID salonId, Instant at) {
        platform.getObject().requireTransaction();
        return functions.revokeClosedSalonSessions(salonId, at);
    }
}
