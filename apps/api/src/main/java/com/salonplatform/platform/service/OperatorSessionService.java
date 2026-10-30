package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.OperatorPrincipal;
import com.salonplatform.platform.dto.OperatorTokens;

/**
 * An operator's console sessions (docs/architecture/operator-console.md, "Operator authentication"):
 * access lasts five minutes and a session seven days from sign-in; refresh rotates once and a spent
 * credential revokes its family.
 */
public interface OperatorSessionService {

    /**
     * Rotates a refresh credential once. A spent credential revokes its whole family, so of two
     * simultaneous renewals at most one succeeds and a replay can never leave a usable family.
     */
    OperatorTokens refresh(String refreshToken);

    /** Revokes the current session: no access or refresh credential of it works afterwards. */
    void signOut();

    /** The current operator: no tenant identity, permissions from the role as stored now. */
    OperatorPrincipal me();
}
