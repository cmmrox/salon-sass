package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.catalogue.CatalogueListings;
import com.salonplatform.identity.SignInAccounts;
import com.salonplatform.identity.inprocess.StaffSeats;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import com.salonplatform.staff.UpcomingMemberBookings;
import com.salonplatform.staff.domain.PerformedServices;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.repository.MemberPhotoRepository;
import com.salonplatform.staff.repository.MemberRepository;
import com.salonplatform.staff.service.TeamMemberService;
import com.salonplatform.staff.service.impl.StaffScope.Scope;
import com.salonplatform.staff.service.impl.TeamViews.Row;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Other modules are asked before a transaction opens ({@code catalogue}'s listing, {@code booking}'s answer, as in
 * decision 094), so no connection waits on them. Every change holds the salon's identity lock first, before it touches
 * any row of this module: the one order everywhere, which {@code identity} also follows when it removes an account, so
 * that seats, links and members cannot deadlock (decision 100). Under that lock two saves run one after the other, so
 * the second sees the first's version, link and seat.
 */
@Service
class TeamMemberServiceImpl implements TeamMemberService {

    /** A salon has at most 200 team members (F06-S01 AC-1). */
    static final int MAX_MEMBERS = 200;

    private final StaffScope scope;
    private final MemberRepository members;
    private final MemberPhotoRepository photos;
    private final TeamRows rows;
    private final TeamViews views;
    private final TeamAudit audit;
    private final CatalogueListings catalogue;
    private final SignInAccounts accounts;
    private final StaffSeats seats;
    private final UpcomingMemberBookings upcoming;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    TeamMemberServiceImpl(
            StaffScope scope,
            MemberRepository members,
            MemberPhotoRepository photos,
            TeamRows rows,
            TeamViews views,
            TeamAudit audit,
            CatalogueListings catalogue,
            SignInAccounts accounts,
            StaffSeats seats,
            UpcomingMemberBookings upcoming,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.scope = scope;
        this.members = members;
        this.photos = photos;
        this.rows = rows;
        this.views = views;
        this.audit = audit;
        this.catalogue = catalogue;
        this.accounts = accounts;
        this.seats = seats;
        this.upcoming = upcoming;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public List<MemberView> list() {
        Scope at = scope.of(StaffScope.TEAM_READ);
        CatalogueListing listing = catalogue.current();
        List<Row> read = reading.execute(status -> {
            List<Member> active = members.active(at.branch());
            Map<UUID, Choice> choices =
                    rows.choices(active.stream().map(Member::getId).toList(), listing);
            return active.stream()
                    .map(member -> Row.of(member, choices.get(member.getId())))
                    .toList();
        });
        return views.all(read, listing, at.actor());
    }

    @Override
    public MemberView get(UUID id) {
        Scope at = scope.of(StaffScope.TEAM_READ);
        CatalogueListing listing = catalogue.current();
        return views.of(reading.execute(status -> rowOf(active(id, at), listing)), listing, at.actor());
    }

    @Override
    public MemberView add(MemberInput input) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        CatalogueListing listing = catalogue.current();
        MemberValidation.Valid valid = MemberValidation.check(input, listing, true);
        Row added = changing(() -> writing.execute(status -> {
            seats.hold();
            if (members.countActive() >= MAX_MEMBERS) {
                throw new ApplicationProblemException(409, ErrorCode.LIMIT_REACHED);
            }
            if (valid.signInAccountId() == null) {
                seats.requireFreeSeat();
            } else {
                requireLinkable(valid.signInAccountId());
            }
            Instant now = clock.instant();
            Member member =
                    members.saveAndFlush(Member.added(at.branch(), valid.profile(), valid.signInAccountId(), now));
            rows.replace(member.getId(), at.branch(), valid.choice(), now);
            audit.record(
                    at.actor(), TeamAudit.ADDED, member.getId(), null, values(member, valid.choice(), listing), now);
            return Row.of(member, valid.choice());
        }));
        return views.of(added, listing, at.actor());
    }

    @Override
    public MemberView save(UUID id, MemberInput input, Long version) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        long expected = requiredVersion(version);
        CatalogueListing listing = catalogue.current();
        MemberValidation.Valid valid = MemberValidation.check(input, listing, null);
        // A stale save is refused for being stale, whatever bookings say about it.
        Choice held = reading.execute(status -> {
            Member member = active(id, at);
            requireVersion(member, expected);
            return rows.choices(List.of(id), listing).get(id);
        });
        refuseLosingBookedServices(id, held, valid.choice(), listing);
        Row saved = changing(() -> writing.execute(status -> {
            seats.hold();
            Member member = active(id, at);
            requireVersion(member, expected);
            Choice before = rows.choices(List.of(id), listing).get(id);
            boolean linkChanges = !Objects.equals(member.getSignInAccountId(), valid.signInAccountId());
            boolean choiceChanges = !before.equals(valid.choice());
            if (!linkChanges && !choiceChanges && profileOf(member).equals(valid.profile())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            if (linkChanges) {
                requireLinkChangeAllowed(valid.signInAccountId());
            }
            Map<String, Object> beforeValues = values(member, before, listing);
            Instant now = clock.instant();
            member.change(valid.profile(), valid.signInAccountId(), now);
            members.flush();
            if (choiceChanges) {
                rows.replace(id, at.branch(), valid.choice(), now);
            }
            audit.record(at.actor(), TeamAudit.CHANGED, id, beforeValues, values(member, valid.choice(), listing), now);
            return Row.of(member, valid.choice());
        }));
        return views.of(saved, listing, at.actor());
    }

    @Override
    public void remove(UUID id, Long version) {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        long expected = requiredVersion(version);
        CatalogueListing listing = catalogue.current();
        reading.execute(status -> {
            requireVersion(active(id, at), expected);
            return id;
        });
        // The booking module's answer, in its own transaction, before this one opens (decision 094's window).
        long booked = upcoming.countFor(id);
        if (booked > 0) {
            throw new ApplicationProblemException(
                    409, ErrorCode.IN_USE, List.of(new FieldProblem("upcomingBookings", Long.toString(booked))));
        }
        changing(() -> writing.execute(status -> {
            seats.hold();
            Member member = active(id, at);
            requireVersion(member, expected);
            Map<String, Object> before =
                    values(member, rows.choices(List.of(id), listing).get(id), listing);
            UUID photoId = member.getPhotoId();
            Instant now = clock.instant();
            member.remove(now);
            members.flush();
            rows.remove(id);
            if (photoId != null) {
                photos.remove(photoId);
            }
            audit.record(at.actor(), TeamAudit.REMOVED, id, before, null, now);
            return id;
        }));
    }

    @Override
    public List<AccountView> signInAccounts() {
        Scope at = scope.of(StaffScope.TEAM_MANAGE);
        if (!at.actor().permissions().contains(StaffScope.STAFF_LIST)) {
            throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
        }
        Map<UUID, UUID> linked = reading.execute(status -> {
            Map<UUID, UUID> byAccount = new HashMap<>();
            for (Member member : members.active(at.branch())) {
                if (member.getSignInAccountId() != null) {
                    byAccount.put(member.getSignInAccountId(), member.getId());
                }
            }
            return byAccount;
        });
        return accounts.active().stream()
                .map(account ->
                        new AccountView(account.id(), account.label(), account.role(), linked.get(account.id())))
                .toList();
    }

    /** A save may not stop someone performing a service they are booked for (AC-8), asked before the transaction. */
    private void refuseLosingBookedServices(UUID id, Choice held, Choice next, CatalogueListing listing) {
        Set<UUID> lost = new HashSet<>(PerformedServices.performed(listing, held));
        lost.removeAll(PerformedServices.performed(listing, next));
        if (lost.isEmpty()) {
            return;
        }
        Set<UUID> booked = upcoming.servicesBookedFor(id, Set.copyOf(lost));
        if (!booked.isEmpty()) {
            throw new ApplicationProblemException(
                    409,
                    ErrorCode.IN_USE,
                    listing.services().stream()
                            .filter(service -> booked.contains(service.id()))
                            .map(service -> new FieldProblem("services", service.name()))
                            .toList());
        }
    }

    /** Unlinking an account that stays takes a seat; linking to a free account frees one, so it needs none. */
    private void requireLinkChangeAllowed(UUID nextAccount) {
        if (nextAccount == null) {
            seats.requireFreeSeat();
        } else {
            requireLinkable(nextAccount);
        }
    }

    private void requireLinkable(UUID accountId) {
        if (!seats.isActiveAccount(accountId) || members.findLinkedTo(accountId).isPresent()) {
            throw MemberValidation.accountNotLinkable();
        }
    }

    private Member active(UUID id, Scope at) {
        return members.findActive(id, at.branch()).orElseThrow(NotFoundException::new);
    }

    private Row rowOf(Member member, CatalogueListing listing) {
        return Row.of(member, rows.choices(List.of(member.getId()), listing).get(member.getId()));
    }

    private static void requireVersion(Member member, long expected) {
        if (member.getVersion() != expected) {
            throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
        }
    }

    private static long requiredVersion(Long version) {
        if (version == null || version < 0) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("version", "must be zero or more")));
        }
        return version;
    }

    private static Member.Profile profileOf(Member member) {
        return new Member.Profile(
                member.getName(), member.getTitle(), member.getHighlights(), member.getBio(), member.isShownOnline());
    }

    /** What the audit keeps of a person: every field they can change, with ids rather than a photo's bytes. */
    private static Map<String, Object> values(Member member, Choice choice, CatalogueListing listing) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", member.getName());
        values.put("title", member.getTitle());
        values.put("highlights", member.getHighlights());
        values.put("bio", member.getBio());
        values.put("shownOnline", member.isShownOnline());
        values.put("categoryIds", ordered(listing.categories(), CatalogueListing.Category::id, choice.categoryIds()));
        values.put("serviceIds", ordered(listing.services(), CatalogueListing.Service::id, choice.serviceIds()));
        values.put("signInAccountId", text(member.getSignInAccountId()));
        values.put("photoId", text(member.getPhotoId()));
        return values;
    }

    private static <T> List<String> ordered(List<T> listed, Function<T, UUID> id, Set<UUID> chosen) {
        return listed.stream()
                .map(id)
                .filter(chosen::contains)
                .map(UUID::toString)
                .toList();
    }

    private static String text(UUID id) {
        return id == null ? null : id.toString();
    }

    /** A lost race on a version or the link's index: nothing is kept, and the caller reads again. */
    static <T> T changing(Supplier<T> change) {
        try {
            return change.get();
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
            throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
        }
    }
}
