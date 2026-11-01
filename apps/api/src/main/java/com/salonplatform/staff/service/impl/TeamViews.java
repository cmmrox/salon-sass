package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.StaffLabels;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.service.TeamMemberService.MemberView;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a member into what a caller may see. Only a caller with {@code staff.list} learns the linked account and its
 * label (decision 100). The labels are asked for after the reading transaction, in identity's own.
 */
@Component
class TeamViews {

    /** What a view is made from, read in a transaction and used after it. */
    record Row(
            UUID id,
            String name,
            String title,
            String highlights,
            String bio,
            boolean shownOnline,
            UUID photoId,
            Choice choice,
            UUID signInAccountId,
            long version) {

        static Row of(Member member, Choice choice) {
            return new Row(
                    member.getId(),
                    member.getName(),
                    member.getTitle(),
                    member.getHighlights(),
                    member.getBio(),
                    member.isShownOnline(),
                    member.getPhotoId(),
                    choice,
                    member.getSignInAccountId(),
                    member.getVersion());
        }
    }

    private final StaffLabels labels;

    TeamViews(StaffLabels labels) {
        this.labels = labels;
    }

    /** The rows as this caller may see them, in the listing's order of categories and services. */
    List<MemberView> all(List<Row> rows, CatalogueListing listing, Principal caller) {
        boolean visible = caller.permissions().contains(StaffScope.STAFF_LIST);
        Map<UUID, String> named = visible
                ? labels.labels(rows.stream()
                        .map(Row::signInAccountId)
                        .filter(Objects::nonNull)
                        .toList())
                : Map.of();
        return rows.stream().map(row -> view(row, listing, visible, named)).toList();
    }

    MemberView of(Row row, CatalogueListing listing, Principal caller) {
        return all(List.of(row), listing, caller).getFirst();
    }

    private static MemberView view(Row row, CatalogueListing listing, boolean visible, Map<UUID, String> named) {
        UUID account = visible ? row.signInAccountId() : null;
        return new MemberView(
                row.id(),
                row.name(),
                row.title(),
                row.highlights(),
                row.bio(),
                row.shownOnline(),
                row.photoId(),
                listing.categories().stream()
                        .map(CatalogueListing.Category::id)
                        .filter(row.choice().categoryIds()::contains)
                        .toList(),
                listing.services().stream()
                        .map(CatalogueListing.Service::id)
                        .filter(row.choice().serviceIds()::contains)
                        .toList(),
                visible,
                account,
                account == null ? null : named.get(account),
                row.version());
    }
}
