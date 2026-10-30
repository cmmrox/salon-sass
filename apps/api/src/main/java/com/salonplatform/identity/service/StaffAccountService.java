package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.Page;
import com.salonplatform.identity.dto.SeedAccount;
import com.salonplatform.identity.dto.StaffView;
import java.util.List;
import java.util.UUID;

/**
 * The salon's staff accounts as the Owner manages them (docs/architecture/identity-and-access.md): created within
 * the plan's staff seats, their role and Professional grant changed, removed; never the last active Owner. A
 * change names the version it was made against.
 */
public interface StaffAccountService {

    /** A new staff account: an email or a mobile, its first password, its role and Professional grant. */
    record StaffInput(String email, String mobile, String password, String role, Boolean professionalGrant) {}

    /** A change of role or Professional grant, made against the version it was read at. */
    record StaffChange(long version, String role, Boolean professionalGrant) {}

    /**
     * Creates a staff account within the plan's staff seats.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code conflict} when another account
     *     holds the email or mobile, and the entitlement refusal when no seat is left
     */
    StaffView create(StaffInput input);

    /** The salon's staff accounts, oldest first. */
    Page<StaffView> list(int page, int size);

    /**
     * Changes a staff account's role or Professional grant. Promoting someone to Owner ends their sessions.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code conflict} when the account
     *     changed since the version named, is inactive, or is the last active Owner being demoted
     */
    StaffView change(UUID staffId, StaffChange change);

    /**
     * Deactivates a staff account and ends its sessions.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code conflict} for the last active
     *     Owner
     */
    void remove(UUID staffId);

    /**
     * Local fixtures only (no production route reaches this): creates each account that no account holds the
     * email or mobile of yet, with its authenticator secret when one is given.
     */
    List<StaffView> seed(List<SeedAccount> accounts);

    /** Local fixtures only: removes every staff and customer record of the salon. */
    void reset();
}
