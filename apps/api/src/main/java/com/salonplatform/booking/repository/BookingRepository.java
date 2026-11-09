package com.salonplatform.booking.repository;

import com.salonplatform.booking.entity.Booking;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

/**
 * The current salon's holds and bookings (tenant filter and row-level security). Every read is a query, so the tenant
 * filter applies to it. A state is named by its stored value; a hold whose expiry has passed is still {@code held} in
 * the row, and every read a caller sees leaves it out by the application's clock ({@code now}).
 */
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Visible to a caller: not an ended hold, and not a hold that has run out. */
    String SHOWN = "b.state not in ('released', 'expired') and (b.state <> 'held' or b.holdExpiresAt > :now)";

    /** Still expected to take place, with a hold that has run out left out (AC-13). */
    String EXPECTED = "b.state in ('pending_payment', 'confirmed', 'arrived', 'in_service')"
            + " or (b.state = 'held' and b.holdExpiresAt > :now)";

    /** Occupies its professional now: live, with a hold that has run out left out (F07-S01 AC-3). */
    String OCCUPYING = "b.state in ('pending_payment', 'confirmed', 'arrived', 'in_service', 'completed')"
            + " or (b.state = 'held' and b.holdExpiresAt > :now)";

    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findBooking(@Param("id") UUID id);

    /** The booking, locked until the transaction ends: a second change waits and is judged against the first's result. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockBooking(@Param("id") UUID id);

    @Query("select b from Booking b where b.idempotencyKey = :key")
    Optional<Booking> findByKey(@Param("key") UUID key);

    @Query("select count(b) > 0 from Booking b where b.reference = :reference")
    boolean referenceTaken(@Param("reference") String reference);

    /**
     * The professional's holds that have run out and lie anywhere over this time, locked: a placement or an undone
     * no-show ends them before it takes the time, so they never block it (AC-3).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from Booking b
             where b.memberId = :member and b.state = 'held' and b.holdExpiresAt <= :now
               and b.startsAt < :until and b.endsAt > :from
             order by b.startsAt, b.id
            """)
    List<Booking> lockRunOutHolds(
            @Param("member") UUID memberId,
            @Param("from") Instant from,
            @Param("until") Instant until,
            @Param("now") Instant now);

    /**
     * Holds that have run out, locked, skipping any another transaction has locked (a placement ending them itself):
     * what the job ends this run (AC-6). Skipping, rather than waiting, means the job never waits on a placement.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select b from Booking b where b.state = 'held' and b.holdExpiresAt <= :now order by b.holdExpiresAt, b.id")
    List<Booking> lockRunOutHolds(@Param("now") Instant now, Pageable limit);

    /**
     * A page of the bookings a caller sees from {@code from} up to {@code until}, by start time. A member is matched
     * only when {@code anyMember} is false; the search matches only when {@code searching} is true, by part of the
     * name (a lowercase {@code like} pattern), the mobile number in E.164, or the reference.
     */
    @Query(
            value = "select b from Booking b where b.startsAt >= :from and b.startsAt < :until and (" + SHOWN + ")"
                    + " and b.state in :states and (:anyMember = true or b.memberId = :member)"
                    + " and (:searching = false or lower(b.customerName) like :name escape '\\'"
                    + " or b.customerMobile = :mobile or b.reference = :reference)"
                    + " order by b.startsAt, b.id",
            countQuery = "select count(b) from Booking b where b.startsAt >= :from and b.startsAt < :until and ("
                    + SHOWN + ") and b.state in :states and (:anyMember = true or b.memberId = :member)"
                    + " and (:searching = false or lower(b.customerName) like :name escape '\\'"
                    + " or b.customerMobile = :mobile or b.reference = :reference)")
    Page<Booking> page(
            @Param("from") Instant from,
            @Param("until") Instant until,
            @Param("now") Instant now,
            @Param("states") Collection<String> states,
            @Param("anyMember") boolean anyMember,
            @Param("member") UUID memberId,
            @Param("searching") boolean searching,
            @Param("name") String namePattern,
            @Param("mobile") String mobile,
            @Param("reference") String reference,
            Pageable page);

    /**
     * The bookings and holds of these members that occupy them now and lie anywhere over the time: what availability
     * counts as taken (F07-S02 AC-1). One read for the whole team.
     */
    @Query("select b from Booking b where b.memberId in :members and b.startsAt < :until and b.endsAt > :from"
            + " and (" + OCCUPYING + ")")
    List<Booking> occupying(
            @Param("members") Collection<UUID> memberIds,
            @Param("from") Instant from,
            @Param("until") Instant until,
            @Param("now") Instant now);

    /** How many of the member's expected bookings have not yet ended. */
    @Query("select count(b) from Booking b where b.memberId = :member and b.endsAt > :now and (" + EXPECTED + ")")
    long countUpcomingForMember(@Param("member") UUID memberId, @Param("now") Instant now);

    /** Which of these services the member has an expected booking for that has not yet ended. */
    @Query("select distinct b.serviceId from Booking b where b.memberId = :member and b.serviceId in :services"
            + " and b.endsAt > :now and (" + EXPECTED + ")")
    List<UUID> servicesBookedFor(
            @Param("member") UUID memberId, @Param("services") Collection<UUID> serviceIds, @Param("now") Instant now);

    /** Whether the service has an expected booking that has not yet ended. */
    @Query("select count(b) > 0 from Booking b where b.serviceId = :service and b.endsAt > :now and (" + EXPECTED + ")")
    boolean anyUpcomingForService(@Param("service") UUID serviceId, @Param("now") Instant now);

    /**
     * For each service with any, how many bookings in these states started at or after {@code from} and before
     * {@code until}; a booking still to come is not counted.
     */
    @Query("select b.serviceId, count(b) from Booking b where b.startsAt >= :from and b.startsAt < :until"
            + " and b.state in :states group by b.serviceId")
    List<Object[]> countByService(
            @Param("from") Instant from, @Param("until") Instant until, @Param("states") Collection<String> states);

    /** The branch's expected bookings starting in the time, by start time. */
    @Query("select b from Booking b where b.branchId = :branch and b.startsAt >= :from and b.startsAt < :until"
            + " and (" + EXPECTED + ") order by b.startsAt, b.id")
    List<Booking> expectedInBranch(
            @Param("branch") UUID branchId,
            @Param("from") Instant from,
            @Param("until") Instant until,
            @Param("now") Instant now);

    /** The member's expected bookings starting in the time, by start time. */
    @Query("select b from Booking b where b.memberId = :member and b.startsAt >= :from and b.startsAt < :until"
            + " and (" + EXPECTED + ") order by b.startsAt, b.id")
    List<Booking> expectedOfMember(
            @Param("member") UUID memberId,
            @Param("from") Instant from,
            @Param("until") Instant until,
            @Param("now") Instant now);
}
