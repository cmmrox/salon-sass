package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.TeamMember;
import com.salonplatform.staff.TeamMembers;
import com.salonplatform.staff.domain.PerformedServices;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads under the salon's tenant filter and row-level security, so another salon's id is simply not found. */
@Service
class TeamMembersImpl implements TeamMembers {

    private final StaffScope scope;
    private final MemberRepository members;
    private final TeamRows rows;
    private final CatalogueListings catalogue;
    private final ReadOnlyWork reading;

    TeamMembersImpl(
            StaffScope scope,
            MemberRepository members,
            TeamRows rows,
            CatalogueListings catalogue,
            ReadOnlyWork reading) {
        this.scope = scope;
        this.members = members;
        this.rows = rows;
        this.catalogue = catalogue;
        this.reading = reading;
    }

    @Override
    public Optional<TeamMember> member(UUID memberId) {
        TenantContext.require();
        return reading.read(() -> members.findAny(memberId).map(TeamMembersImpl::contract));
    }

    @Override
    public List<TeamMember> performing(UUID serviceId) {
        Scope at = scope.publicly();
        CatalogueListing listing = catalogue.current();
        return reading.read(() -> {
            List<Member> active = members.active(at.branch());
            Map<UUID, Choice> choices =
                    rows.choices(active.stream().map(Member::getId).toList(), listing);
            return active.stream()
                    .filter(member -> PerformedServices.performed(listing, choices.get(member.getId()))
                            .contains(serviceId))
                    .map(TeamMembersImpl::contract)
                    .toList();
        });
    }

    @Override
    public Set<UUID> servicesOf(UUID memberId) {
        TenantContext.require();
        CatalogueListing listing = catalogue.current();
        return reading.read(() -> performedBy(members, rows, memberId, listing));
    }

    /** The services the member performs given the listing, read in the transaction that is open. */
    static Set<UUID> performedBy(MemberRepository members, TeamRows rows, UUID memberId, CatalogueListing listing) {
        Optional<Member> member = members.findAny(memberId).filter(found -> !found.isRemoved());
        if (member.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(PerformedServices.performed(
                listing, rows.choices(List.of(memberId), listing).get(memberId)));
    }

    @Override
    public Optional<TeamMember> linkedTo(UUID accountId) {
        TenantContext.require();
        return reading.read(() -> members.findLinkedTo(accountId).map(TeamMembersImpl::contract));
    }

    static TeamMember contract(Member member) {
        return new TeamMember(member.getId(), member.getName(), member.isRemoved(), member.isShownOnline());
    }
}
