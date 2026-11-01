package com.salonplatform.booking.entity;

import com.salonplatform.booking.BookingRules;
import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.DepositRule;
import com.salonplatform.booking.Rescheduling;
import com.salonplatform.booking.Retention;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * One version of a branch's booking rules (F04-S04, decision 089): written once by a save and never changed,
 * so a booking that keeps its id keeps its terms. Hibernate never updates it ({@link Immutable}), the
 * application role has no update or delete grant, and the database refuses an update by any role. Its number
 * is the branch's previous version's plus one; the unique number keeps one of two saves made from the same
 * version.
 */
@Entity
@Immutable
@Table(name = "booking_rules_version")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingRulesVersion {

    private static final String NONE = "none";
    private static final String PERCENTAGE = "percentage";
    private static final String AMOUNT = "amount";
    private static final String NOTHING = "nothing";
    private static final String DEPOSIT = "deposit";
    private static final String SHARE = "share";

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Column(name = "version", nullable = false, updatable = false)
    private long version;

    @Column(name = "slot_interval_minutes", nullable = false, updatable = false)
    private int slotIntervalMinutes;

    @Column(name = "minimum_notice_minutes", nullable = false, updatable = false)
    private int minimumNoticeMinutes;

    @Column(name = "booking_horizon_days", nullable = false, updatable = false)
    private int bookingHorizonDays;

    @Column(name = "deposit_kind", nullable = false, updatable = false)
    private String depositKind;

    @Column(name = "deposit_percent", updatable = false)
    private Integer depositPercent;

    @Column(name = "deposit_cap_minor", updatable = false)
    private Long depositCapMinor;

    @Column(name = "deposit_amount_minor", updatable = false)
    private Long depositAmountMinor;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency;

    @Column(name = "free_cancellation_hours", nullable = false, updatable = false)
    private int freeCancellationHours;

    @Column(name = "late_cancellation_kind", nullable = false, updatable = false)
    private String lateCancellationKind;

    @Column(name = "late_cancellation_percent", updatable = false)
    private Integer lateCancellationPercent;

    @Column(name = "no_show_kind", nullable = false, updatable = false)
    private String noShowKind;

    @Column(name = "no_show_percent", updatable = false)
    private Integer noShowPercent;

    @Column(name = "reschedule_limit", updatable = false)
    private Integer rescheduleLimit;

    @Column(name = "reschedule_until_hours", updatable = false)
    private Integer rescheduleUntilHours;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "changed_fields", nullable = false, updatable = false)
    private String[] changedFields;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    /**
     * A new version of the branch's rules, which Hibernate stamps with the current salon.
     *
     * @param version the branch's previous version plus one (1 for its first)
     * @param changedFields the rules it changes from the previous version
     */
    public static BookingRulesVersion saved(
            UUID branchId,
            long version,
            BookingTerms terms,
            String currency,
            List<String> changedFields,
            UUID createdBy,
            Instant at) {
        BookingRulesVersion row = new BookingRulesVersion();
        row.id = UUID.randomUUID();
        row.branchId = branchId;
        row.version = version;
        row.slotIntervalMinutes = terms.slotIntervalMinutes();
        row.minimumNoticeMinutes = terms.minimumNoticeMinutes();
        row.bookingHorizonDays = terms.bookingHorizonDays();
        switch (terms.deposit()) {
            case DepositRule.None _ -> row.depositKind = NONE;
            case DepositRule.Percentage percentage -> {
                row.depositKind = PERCENTAGE;
                row.depositPercent = percentage.percent();
                row.depositCapMinor = percentage.capMinor();
            }
            case DepositRule.Amount amount -> {
                row.depositKind = AMOUNT;
                row.depositAmountMinor = amount.amountMinor();
            }
        }
        row.currency = currency;
        row.freeCancellationHours = terms.freeCancellationHours();
        row.lateCancellationKind = kind(terms.lateCancellation());
        row.lateCancellationPercent = percent(terms.lateCancellation());
        row.noShowKind = kind(terms.noShow());
        row.noShowPercent = percent(terms.noShow());
        switch (terms.reschedule()) {
            case Rescheduling.NotOnline _ -> row.rescheduleLimit = 0;
            case Rescheduling.Limited limited -> {
                row.rescheduleLimit = limited.times();
                row.rescheduleUntilHours = limited.untilHours();
            }
            case Rescheduling.Unlimited unlimited -> row.rescheduleUntilHours = unlimited.untilHours();
        }
        row.changedFields = changedFields.toArray(String[]::new);
        row.createdBy = createdBy;
        row.createdAt = at;
        row.updatedAt = at;
        return row;
    }

    /** The rules this version holds. */
    public BookingTerms terms() {
        DepositRule deposit = switch (depositKind) {
            case PERCENTAGE -> new DepositRule.Percentage(depositPercent, depositCapMinor);
            case AMOUNT -> new DepositRule.Amount(depositAmountMinor);
            default -> new DepositRule.None();
        };
        Rescheduling reschedule;
        if (rescheduleLimit == null) {
            reschedule = new Rescheduling.Unlimited(rescheduleUntilHours);
        } else if (rescheduleLimit == 0) {
            reschedule = new Rescheduling.NotOnline();
        } else {
            reschedule = new Rescheduling.Limited(rescheduleLimit, rescheduleUntilHours);
        }
        return new BookingTerms(
                slotIntervalMinutes,
                minimumNoticeMinutes,
                bookingHorizonDays,
                deposit,
                freeCancellationHours,
                retention(lateCancellationKind, lateCancellationPercent),
                retention(noShowKind, noShowPercent),
                reschedule);
    }

    /** This version as other modules read it. */
    public BookingRules rules() {
        return new BookingRules(id, version, branchId, currency, createdAt, terms());
    }

    /** The rules this version changed from the one before it. */
    public List<String> changedFieldNames() {
        return List.of(changedFields);
    }

    private static String kind(Retention retention) {
        return switch (retention) {
            case Retention.Nothing _ -> NOTHING;
            case Retention.Deposit _ -> DEPOSIT;
            case Retention.Share _ -> SHARE;
        };
    }

    private static Integer percent(Retention retention) {
        return retention instanceof Retention.Share share ? share.percent() : null;
    }

    private static Retention retention(String kind, Integer percent) {
        return switch (kind) {
            case NOTHING -> new Retention.Nothing();
            case SHARE -> new Retention.Share(percent);
            default -> new Retention.Deposit();
        };
    }
}
