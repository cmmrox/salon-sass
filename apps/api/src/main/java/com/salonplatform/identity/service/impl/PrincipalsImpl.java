package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.identity.service.SessionService;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import org.springframework.stereotype.Service;

@Service
class PrincipalsImpl implements Principals {

    private final SessionService sessions;

    PrincipalsImpl(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public Principal current() {
        return sessions.me();
    }

    @Override
    public Principal require(String permission) {
        Principal principal = current();
        if (!principal.permissions().contains(permission))
            throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
        return principal;
    }
}
