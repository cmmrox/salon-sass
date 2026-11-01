package com.salonplatform.branding;

import java.util.UUID;

/**
 * The current salon's branches, for any module that keeps a branch scope (decisions 073 and 083). A
 * module holds a branch as an id, with no key into branding's tables, and checks the id here when it
 * writes it. The salon is the request's, never a parameter. Each call opens its own read-only tenant
 * transaction.
 *
 * <p>In R1 every salon has exactly one branch, its default; F18 adds more. A caller never assumes there
 * is only one.
 */
public interface SalonBranches {

    /**
     * The current salon's default branch.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon,
     *     or the salon has no default branch
     * @throws com.salonplatform.shared.tenancy.TenantSuspendedException when the salon is suspended
     */
    SalonBranch defaultBranch();

    /**
     * Whether a branch id is one of the current salon's branches. Another salon's branch, and an id that
     * names no branch, both answer false: the two cannot be told apart.
     */
    boolean belongsToCurrentSalon(UUID branchId);
}
