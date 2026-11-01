package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.shared.persistence.NewEntities;
import com.salonplatform.staff.domain.PerformedServices;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import com.salonplatform.staff.entity.MemberCategory;
import com.salonplatform.staff.entity.MemberSingleService;
import com.salonplatform.staff.repository.MemberCategoryRepository;
import com.salonplatform.staff.repository.MemberSingleServiceRepository;
import java.time.Instant;
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

    TeamRows(MemberCategoryRepository categories, MemberSingleServiceRepository services) {
        this.categories = categories;
        this.services = services;
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
