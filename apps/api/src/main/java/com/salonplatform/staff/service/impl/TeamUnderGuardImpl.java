package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.TeamMember;
import com.salonplatform.staff.inprocess.TeamUnderGuard;
import com.salonplatform.staff.repository.MemberRepository;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The team as {@link TeamMembersImpl} reads it, in the caller's transaction (decision 119). */
@Service
class TeamUnderGuardImpl implements TeamUnderGuard {

    private final MemberRepository members;
    private final TeamRows rows;

    TeamUnderGuardImpl(MemberRepository members, TeamRows rows) {
        this.members = members;
        this.rows = rows;
    }

    @Override
    public Optional<TeamMember> member(UUID memberId) {
        inTheCallersTransaction();
        return members.findAny(memberId).map(TeamMembersImpl::contract);
    }

    @Override
    public Set<UUID> servicesOf(UUID memberId, CatalogueListing listing) {
        inTheCallersTransaction();
        return TeamMembersImpl.performedBy(members, rows, memberId, listing);
    }

    private static void inTheCallersTransaction() {
        TenantContext.require();
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("The team is checked under the guard in the caller's transaction");
        }
    }
}
