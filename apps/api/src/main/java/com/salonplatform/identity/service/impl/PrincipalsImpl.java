package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.identity.service.SessionService;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import org.springframework.stereotype.Service;

@Service
class PrincipalsImpl implements Principals {

    private final SessionService sessions;

    private final ReadOnlyWork reading;

    PrincipalsImpl(SessionService sessions, ReadOnlyWork reading) {
        this.sessions = sessions;
        this.reading = reading;
    }

    /** Who the request is, read in a read-only transaction of its own ({@link ReadOnlyWork}), taking no lock. */
    @Override
    public Principal current() {
        return reading.read(sessions::me);
    }

    @Override
    public Principal require(String permission) {
        Principal principal = current();
        if (!principal.permissions().contains(permission))
            throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
        return principal;
    }
}
