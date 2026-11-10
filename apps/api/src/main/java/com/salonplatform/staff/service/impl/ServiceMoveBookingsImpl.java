package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.ServiceMoveBookings;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.staff.UpcomingMemberBookings;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.entity.MemberCategory;
import com.salonplatform.staff.entity.MemberSingleService;
import com.salonplatform.staff.repository.MemberCategoryRepository;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.repository.MemberSingleServiceRepository;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The catalogue's question before a service moves category (BUG-F06-002). A person performs a service through its
 * category performed whole or as a single service (decisions 099, 100, {@code PerformedServices}), so a move takes it
 * from exactly those who perform the old category whole, do not perform the new one whole and do not hold it singly.
 * Of those, the ones with an upcoming booking for it are asked of {@code booking}, one person at a time, outside this
 * module's own read.
 */
@Service
class ServiceMoveBookingsImpl implements ServiceMoveBookings {

    private final StaffScope scope;
    private final MemberRepository members;
    private final MemberCategoryRepository categories;
    private final MemberSingleServiceRepository singles;
    private final UpcomingMemberBookings upcoming;
    private final TransactionTemplate reading;

    ServiceMoveBookingsImpl(
            StaffScope scope,
            MemberRepository members,
            MemberCategoryRepository categories,
            MemberSingleServiceRepository singles,
            UpcomingMemberBookings upcoming,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.members = members;
        this.categories = categories;
        this.singles = singles;
        this.upcoming = upcoming;
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
        // Its own read, never the caller's transaction (decision 119): the catalogue asks again inside its own.
        this.reading.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public List<BookedMember> losingBookedService(UUID serviceId, UUID fromCategoryId, UUID toCategoryId) {
        TenantContext.require();
        if (fromCategoryId == null || fromCategoryId.equals(toCategoryId)) {
            return List.of();
        }
        Scope at = scope.publicly();
        List<Member> losing = reading.execute(status -> {
            List<Member> active = members.active(at.branch());
            List<UUID> ids = active.stream().map(Member::getId).toList();
            Map<UUID, Set<UUID>> whole = categories.ofMembers(ids).stream()
                    .collect(Collectors.groupingBy(
                            MemberCategory::getMemberId,
                            Collectors.mapping(MemberCategory::getCategoryId, Collectors.toSet())));
            Set<UUID> holdingItSingly = singles.ofMembers(ids).stream()
                    .filter(row -> row.getServiceId().equals(serviceId))
                    .map(MemberSingleService::getMemberId)
                    .collect(Collectors.toSet());
            return active.stream()
                    .filter(member -> {
                        Set<UUID> performed = whole.getOrDefault(member.getId(), Set.of());
                        return performed.contains(fromCategoryId)
                                && !performed.contains(toCategoryId)
                                && !holdingItSingly.contains(member.getId());
                    })
                    .toList();
        });
        return losing.stream()
                .filter(member -> !upcoming.servicesBookedFor(member.getId(), Set.of(serviceId))
                        .isEmpty())
                .map(member -> new BookedMember(member.getId(), member.getName()))
                .toList();
    }
}
