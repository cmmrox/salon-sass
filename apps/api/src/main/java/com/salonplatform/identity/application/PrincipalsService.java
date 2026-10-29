package com.salonplatform.identity.application;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import org.springframework.stereotype.Service;

@Service
class PrincipalsService implements Principals {

    private final SessionAccess access;

    PrincipalsService(SessionAccess access) {
        this.access = access;
    }

    @Override
    public Principal current() {
        return access.me();
    }

    @Override
    public Principal require(String permission) {
        Principal principal = current();
        if (!principal.permissions().contains(permission))
            throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
        return principal;
    }
}
