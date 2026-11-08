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
    default int seed(List<AccountInput> inputs) {
        return seed(inputs, false, null);
    }

    /**
     * Local fixtures only: as {@link #seed(List)}, except that with {@code keepExisting} an account that exists
     * already (whoever created it) is left exactly as it is and only the missing ones are created.
     *
     * @param developmentPassword the local roster's development password (decision 099), accepted exactly as
     *     given although it is below the password policy; {@code null} accepts only the policy's passwords
     * @return how many accounts were seeded
     */
    int seed(List<AccountInput> inputs, boolean keepExisting, String developmentPassword);

    /**
     * Local fixtures only: clears the synthetic accounts' MFA challenges and consumed steps, revokes
     * their sessions, and clears their email counters and every operator address counter. Salons,
     * plans and other accounts are untouched.
     */
    void resetFixtures();
}
