package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.AccountInput;
import java.util.List;

/**
 * Creating operator accounts outside the console: the explicit offline bootstrap command, and the
 * local fixture hook's synthetic accounts. There is no public registration, reset or recovery, and no
 * environment creates a default operator when it starts.
 */
public interface OperatorAccountService {

    /**
     * The offline bootstrap: creates the operator when no account has that email, with no MFA secret
     * (first sign-in enrolls). An existing account is left unchanged.
     *
     * @return whether an account was created
     */
    boolean bootstrap(AccountInput input);

    /**
     * Local fixtures only: creates each synthetic account, or brings an existing synthetic one to the
     * given state (password, name, role, and the TOTP secret or none). An account not created by this
     * hook is refused, never changed.
     *
     * @return how many accounts were seeded
     */
    int seed(List<AccountInput> inputs);

    /**
     * Local fixtures only: clears the synthetic accounts' MFA challenges and consumed steps, revokes
     * their sessions, and clears their email counters and every operator address counter. Salons,
     * plans and other accounts are untouched.
     */
    void resetFixtures();
}
