package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.inprocess.StaffSeats;
import com.salonplatform.identity.inprocess.TeamMemberSeats;
import com.salonplatform.identity.repository.IdentityLock;
import com.salonplatform.identity.repository.StaffAccountRepository;
import com.salonplatform.shared.entitlements.SalonEntitlements;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The one place seats are counted against the plan (decisions 099, 100), used by staff-account creation here and by
 * {@code staff} through {@link StaffSeats}. Every method joins the caller's transaction.
 */
@Service
class StaffSeatsImpl implements StaffSeats {

    private final IdentityLock lock;
    private final StaffAccountRepository accounts;
    private final SalonEntitlements entitlements;
    private final ObjectProvider<TeamMemberSeats> members;
    private final Clock clock;

    StaffSeatsImpl(
            IdentityLock lock,
            StaffAccountRepository accounts,
            SalonEntitlements entitlements,
            ObjectProvider<TeamMemberSeats> members,
            Clock clock) {
        this.lock = lock;
        this.accounts = accounts;
        this.entitlements = entitlements;
        this.members = members;
        this.clock = clock;
    }

    @Override
    public void hold() {
        requireWritableTransaction();
        lock.lockCurrentSalon(clock.instant());
    }

    @Override
    public void requireFreeSeat() {
        requireWritableTransaction();
        entitlements.forCurrentSalon().ifPresent(limits -> {
            if (seatsUsed() >= limits.staffSeats()) {
                throw SalonEntitlements.exceeded(SalonEntitlements.STAFF_SEATS, limits.staffSeats());
            }
        });
    }

    @Override
    public boolean isActiveAccount(UUID accountId) {
        requireWritableTransaction();
        return accounts.findById(accountId)
                .filter(account -> account.isActive())
                .isPresent();
    }

    /** Active accounts, plus active members with no linked account; with no {@code staff} there are none of those. */
    private long seatsUsed() {
        TeamMemberSeats team = members.getIfAvailable();
        return accounts.countByActiveTrue() + (team == null ? 0 : team.activeMembersWithoutAccount());
    }

    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Staff seats are held inside the writable transaction that takes them");
        }
    }
}
