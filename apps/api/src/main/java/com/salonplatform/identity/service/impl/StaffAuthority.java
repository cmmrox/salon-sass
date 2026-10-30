package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.entity.AccessSession;
import com.salonplatform.identity.entity.StaffAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who the request acts as among the salon's staff, and whether their role holds a permission: checked in the use
 * case on every call, from the database, never from the token (docs/architecture/identity-and-access.md).
 */
@Component
@RequiredArgsConstructor
class StaffAuthority {

    private final SessionAccess sessions;

    record SignedIn(StaffAccount account, AccessSession session) {}

    SignedIn signedIn() {
        SessionAccess.Auth auth = sessions.staffAuth();
        return new SignedIn(auth.staff(), auth.session());
    }

    /** The signed-in staff member, whose role must hold the permission. */
    SignedIn permitted(String permission) {
        SignedIn current = signedIn();
        StaffAccount account = current.account();
        if (!account.getRole().permissions(account.isProfessionalGrant()).contains(permission)) {
            throw IdentityProblems.permissionDenied();
        }
        return current;
    }

    /** A read a support session (F03-S04) may make as the Owner would; otherwise the staff check. */
    void requireReadable(String permission) {
        if (!SessionAccess.supportMay(permission)) {
            permitted(permission);
        }
    }
}
