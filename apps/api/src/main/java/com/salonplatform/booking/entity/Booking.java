package com.salonplatform.booking.entity;

import com.salonplatform.booking.domain.BookingChannel;
import com.salonplatform.booking.domain.BookingState;
import com.salonplatform.booking.domain.BookingStateMachine;
import com.salonplatform.booking.domain.Occupation;
import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * A hold or a booking (F07-S01): one row with a state, which changes only through {@link #moveTo}, after
 * {@link BookingStateMachine} has allowed it. What it was booked on (the terms version, the variant, the segments, the
 * price and the booking rules version) is written once and never changed (CAT-04, decision 089). The time it occupies
 * its professional is a multirange the exclusion constraint compares, written from {@link Occupation}.
 */
@Entity
@Table(name = "booking_appointment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    /** How long a hold lasts until F07-S02 makes it a setting (BKG-04). */
    private static final java.time.Duration HOLD = java.time.Duration.ofMinutes(10);

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "reference", nullable = false, updatable = false)
    private String reference;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "member_name", nullable = false, updatable = false)
    private String memberName;

    @Column(name = "service_id", nullable = false, updatable = false)
    private UUID serviceId;

    @Column(name = "service_terms_id", nullable = false, updatable = false)
    private UUID serviceTermsId;

    @Column(name = "service_name", nullable = false, updatable = false)
    private String serviceName;

    @Column(name = "variant_id", updatable = false)
    private UUID variantId;

    @Column(name = "variant_name", updatable = false)
    private String variantName;

    @Column(name = "rules_version_id", nullable = false, updatable = false)
    private UUID rulesVersionId;

    @Column(name = "starts_at", nullable = false, updatable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false, updatable = false)
    private Instant endsAt;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "segment_kinds", nullable = false, updatable = false)
    private String[] segmentKinds;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "segment_minutes", nullable = false, updatable = false)
    private Integer[] segmentMinutes;

    @ColumnTransformer(read = "occupied::text", write = "?::tstzmultirange")
    @Column(name = "occupied", nullable = false, updatable = false, columnDefinition = "tstzmultirange")
    private String occupied;

    @Column(name = "price_minor", nullable = false, updatable = false)
    private long priceMinor;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "customer_name", nullable = false, updatable = false)
    private String customerName;

    @Column(name = "customer_mobile", nullable = false, updatable = false)
    private String customerMobile;

    @Column(name = "channel", nullable = false, updatable = false)
    private String channel;

    @Column(name = "hold_expires_at", updatable = false)
    private Instant holdExpiresAt;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, updatable = false)
    private String requestFingerprint;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** What a hold is placed with. */
    public record Terms(
            UUID memberId,
            String memberName,
            UUID serviceId,
            UUID serviceTermsId,
            String serviceName,
            UUID variantId,
            String variantName,
            UUID rulesVersionId,
            List<ServiceSegment> segments,
            long priceMinor,
            String currency) {}

    /** Who it is for, where it came from, and the request that placed it. */
    public record Request(
            String customerName,
            String customerMobile,
            BookingChannel channel,
            UUID idempotencyKey,
            String fingerprint,
            UUID createdBy) {}

    /** A new hold on the professional's time from {@code startsAt}, lasting {@link #HOLD} from {@code now}. */
    public static Booking hold(
            UUID branchId, String reference, Terms terms, Request request, Instant startsAt, Instant now) {
        Occupation occupation = Occupation.of(startsAt, terms.segments());
        Booking booking = new Booking();
        booking.id = UUID.randomUUID();
        booking.branchId = branchId;
        booking.reference = reference;
        booking.state = BookingState.HELD.value();
        booking.memberId = terms.memberId();
        booking.memberName = terms.memberName();
        booking.serviceId = terms.serviceId();
        booking.serviceTermsId = terms.serviceTermsId();
        booking.serviceName = terms.serviceName();
        booking.variantId = terms.variantId();
        booking.variantName = terms.variantName();
        booking.rulesVersionId = terms.rulesVersionId();
        booking.startsAt = occupation.startsAt();
        booking.endsAt = occupation.endsAt();
        booking.segmentKinds = terms.segments().stream()
                .map(segment -> segment.kind().name().toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
        booking.segmentMinutes =
                terms.segments().stream().map(ServiceSegment::minutes).toArray(Integer[]::new);
        booking.occupied = occupation.literal();
        booking.priceMinor = terms.priceMinor();
        booking.currency = terms.currency();
        booking.customerName = request.customerName();
        booking.customerMobile = request.customerMobile();
        booking.channel = request.channel().value();
        booking.holdExpiresAt = now.plus(HOLD);
        booking.idempotencyKey = request.idempotencyKey();
        booking.requestFingerprint = request.fingerprint();
        booking.createdBy = request.createdBy();
        booking.createdAt = now;
        booking.updatedAt = now;
        return booking;
    }

    public BookingState state() {
        return BookingState.of(state);
    }

    public BookingChannel channel() {
        return BookingChannel.of(channel);
    }

    /** The segments as booked. */
    public List<ServiceSegment> segments() {
        List<ServiceSegment> segments = new ArrayList<>();
        for (int i = 0; i < segmentKinds.length; i++) {
            segments.add(new ServiceSegment(
                    SegmentKind.valueOf(segmentKinds[i].toUpperCase(Locale.ROOT)), segmentMinutes[i]));
        }
        return List.copyOf(segments);
    }

    /** What the state machine reads of it. */
    public BookingStateMachine.Situation situation(ZoneId zone) {
        return new BookingStateMachine.Situation(state(), startsAt, holdExpiresAt, zone);
    }

    /** Takes the state the machine allowed; the caller records the change in the history. */
    public void moveTo(BookingState next, Instant at) {
        state = next.value();
        updatedAt = at;
    }
}
