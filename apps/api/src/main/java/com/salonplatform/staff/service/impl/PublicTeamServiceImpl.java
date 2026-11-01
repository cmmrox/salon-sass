package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.staff.domain.PerformedServices;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.service.PublicTeamService;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The listing is asked for first, in catalogue's own transaction; then two reads of this module in one. */
@Service
class PublicTeamServiceImpl implements PublicTeamService {

    private final StaffScope scope;
    private final MemberRepository members;
    private final TeamRows rows;
    private final CatalogueListings catalogue;
    private final TransactionTemplate reading;

    PublicTeamServiceImpl(
            StaffScope scope,
            MemberRepository members,
            TeamRows rows,
            CatalogueListings catalogue,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.members = members;
        this.rows = rows;
        this.catalogue = catalogue;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public List<PublicMember> team() {
        Scope at = scope.publicly();
        CatalogueListing listing = catalogue.current();
        Set<UUID> bookable = listing.services().stream()
                .filter(CatalogueListing.Service::bookableOnline)
                .map(CatalogueListing.Service::id)
                .collect(Collectors.toSet());
        return reading.execute(status -> {
            List<Member> shown = members.active(at.branch()).stream()
                    .filter(Member::isShownOnline)
                    .toList();
            Map<UUID, Choice> choices =
                    rows.choices(shown.stream().map(Member::getId).toList(), listing);
            return shown.stream()
                    .map(member -> profile(member, choices.get(member.getId()), listing, bookable))
                    .filter(profile -> !profile.serviceIds().isEmpty())
                    .toList();
        });
    }

    private static PublicMember profile(Member member, Choice choice, CatalogueListing listing, Set<UUID> bookable) {
        return new PublicMember(
                member.getId(),
                member.getName(),
                member.getTitle(),
                member.getHighlights(),
                member.getBio(),
                member.getPhotoId(),
                PerformedServices.performed(listing, choice).stream()
                        .filter(bookable::contains)
                        .toList());
    }
}
