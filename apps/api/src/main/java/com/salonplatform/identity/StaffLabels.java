package com.salonplatform.identity;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * How the current salon's staff accounts are named to its settings users, for a module that records who made
 * a change ({@code booking}'s rule versions, F04-S04): an account's email, else its mobile number, exactly as
 * the workspace staff list (F02) already shows it to the Owner and Manager. The salon is the request's, never a
 * parameter. Each call opens its own read-only tenant transaction.
 */
public interface StaffLabels {

    /**
     * The label of each id that names one of the current salon's staff accounts. An id of another salon's
     * account, and an id that names no account, are both absent: the two cannot be told apart.
     */
    Map<UUID, String> labels(Collection<UUID> staffIds);
}
