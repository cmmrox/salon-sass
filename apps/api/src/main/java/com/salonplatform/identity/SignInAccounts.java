package com.salonplatform.identity;

import java.util.List;

/**
 * The salon's sign-in accounts as {@code staff} offers them for linking to a team member (F06-S01, decision 100). The
 * salon is the request's, never a parameter. Opens its own read-only tenant transaction. It checks no permission: the
 * caller decides who may see this list, and only someone with {@code staff.list} may.
 */
public interface SignInAccounts {

    /** The current salon's active accounts, oldest first. */
    List<SignInAccount> active();
}
