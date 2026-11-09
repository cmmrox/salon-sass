package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.domain.PerformedServices;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import com.salonplatform.staff.entity.MemberCategory;
import com.salonplatform.staff.entity.MemberLeave;
import com.salonplatform.staff.entity.MemberSingleService;
import com.salonplatform.staff.repository.MemberCategoryRepository;
import com.salonplatform.staff.repository.MemberLeaveRepository;
import com.salonplatform.staff.repository.MemberSingleServiceRepository;
import com.salonplatform.staff.service.TeamMemberService.NextLeave;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The rows of what members perform, read in two queries however many members there are, and written by replacing them.
 * Joins the caller's transaction.
 */
@Component
class TeamRows {

    private final MemberCategoryRepository categories;
    private final MemberSingleServiceRepository services;
    private final MemberLeaveRepository leaves;

    TeamRows(
            MemberCategoryRepository categories, MemberSingleServiceRepository services, MemberLeaveRepository leaves) {
        this.categories = categories;
        this.services = services;
        this.leaves = leaves;
    }

    /** Each member's current or next leave by the salon's today, with its type; a member with none is not in the answer. */
    Map<UUID, NextLeave> nextLeaves(Collection<UUID> memberIds, LocalDate today) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, NextLeave> next = new HashMap<>();
        for (MemberLeave leave : leaves.nextOf(memberIds, today)) {
            next.put(leave.getMemberId(), new NextLeave(leave.getFirstDay(), leave.getLastDay(), leave.type()));
        }
        return next;
    }

    /** One member's current or next leave, or null for none. */
    NextLeave nextLeave(UUID memberId, LocalDate today) {
        return nextLeaves(List.of(memberId), today).get(memberId);
    }

    /** Each member's choice as it reads now against the listing; a member with no rows has an empty one. */
    Map<UUID, Choice> choices(Collection<UUID> memberIds, CatalogueListing listing) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<UUID>> whole = new HashMap<>();
        for (MemberCategory row : categories.ofMembers(memberIds)) {
            whole.computeIfAbsent(row.getMemberId(), key -> new ArrayList<>()).add(row.getCategoryId());
        }
        Map<UUID, List<UUID>> single = new HashMap<>();
        for (MemberSingleService row : services.ofMembers(memberIds)) {
            single.computeIfAbsent(row.getMemberId(), key -> new ArrayList<>()).add(row.getServiceId());
        }
        Map<UUID, Choice> choices = new HashMap<>();
        for (UUID member : memberIds) {
            choices.put(
                    member,
                    PerformedServices.current(
                            listing, whole.getOrDefault(member, List.of()), single.getOrDefault(member, List.of())));
        }
        return choices;
    }

    /** Replaces a member's rows with this choice. */
    void replace(UUID memberId, UUID branchId, Choice choice, Instant at) {
        remove(memberId);
        persistAll(categories, choice.categoryIds(), id -> MemberCategory.of(branchId, memberId, id, at));
        persistAll(services, choice.serviceIds(), id -> MemberSingleService.of(branchId, memberId, id, at));
    }

    /** Removes a member's rows. */
    void remove(UUID memberId) {
        categories.removeOf(memberId);
        services.removeOf(memberId);
    }

    private static <T> void persistAll(
            NewEntities<T> repository, Collection<UUID> ids, java.util.function.Function<UUID, T> row) {
        ids.forEach(id -> repository.persist(row.apply(id)));
    }
}
