package com.salonplatform.platform.application;

import com.salonplatform.identity.inprocess.IdentityProvisioning;
import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.ProvisioningRegions;
import com.salonplatform.platform.infrastructure.BillingLedger;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.entitlements.SalonEntitlements;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * An Admin's subscription actions for one salon (docs/architecture/operator-console.md): read the
 * subscription, start one for an unbilled salon (idempotent; it begins its own 14-day trial), change
 * the monthly amount or payment method (from the next invoice; an issued invoice never changes), and
 * move the salon to another plan, refused while its usage exceeds the new plan's limits. Support
 * operators have none of these permissions.
 */
@Service
public class SubscriptionBilling {

    static final String START = "subscription.start";

    @Schema(requiredProperties = {"start", "end"})
    public record Period(Instant start, Instant end) {}

    @Schema(
            requiredProperties = {
                "version",
                "state",
                "plan",
                "monthlyAmount",
                "currency",
                "paymentMethod",
                "trialEndsAt",
                "currentPeriod",
                "nextInvoiceAt",
                "readOnlyAt"
            })
    public record SubscriptionView(
            long version,

            @Schema(allowableValues = {"trialing", "active", "past_due", "read_only"})
            String state,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            SalonDirectory.PlanSummary plan,

            long monthlyAmount,
            String currency,

            @Schema(allowableValues = {"card", "bank_transfer"})
            String paymentMethod,

            Instant trialEndsAt,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Period currentPeriod,

            Instant nextInvoiceAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Instant readOnlyAt) {}

    /** A start request as received. */
    public record StartInput(Long monthlyAmount, String paymentMethod, String currency) {}

    /** A change request as received; absent fields stay as they are. */
    public record ChangeInput(Long version, Long monthlyAmount, String paymentMethod) {}

    /** A plan move as received. */
    public record PlanMoveInput(Long version, UUID planId) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final ProvisioningReceipts receipts;
    private final BillingLedger ledger;
    private final SubscriptionStates states;
    private final SalonDirectory salons;
    private final IdentityProvisioning identity;
    private final PlatformAudit audit;
    private final JsonMapper json;
    private final Clock clock;

    public SubscriptionBilling(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            ProvisioningReceipts receipts,
            BillingLedger ledger,
            SubscriptionStates states,
            SalonDirectory salons,
            IdentityProvisioning identity,
            PlatformAudit audit,
            JsonMapper json,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.receipts = receipts;
        this.ledger = ledger;
        this.states = states;
        this.salons = salons;
        this.identity = identity;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** The view of a salon's subscription, inside a platform transaction. */
    SubscriptionView view(BillingLedger.Subscription subscription, Instant now) {
        BillingLedger.Salon salon = ledger.salon(subscription.salonId(), false).orElseThrow();
        SalonDirectory.PlanSummary plan = salon.planId() == null
                ? null
                : new SalonDirectory.PlanSummary(salon.planId(), salon.planKey(), salon.planName());
        Period period = ledger.latestInvoice(subscription.id())
                .map(invoice -> new Period(invoice.periodStart(), invoice.periodEnd()))
                .orElse(null);
        return new SubscriptionView(
                subscription.version(),
                subscription.state().value(),
                plan,
                subscription.monthlyAmount(),
                subscription.currency(),
                subscription.paymentMethod(),
                subscription.trialEndsAt(),
                period,
                subscription.nextInvoiceAt(),
                ledger.readOnlyAt(subscription, now));
    }

    public SubscriptionView get(UUID salonId) {
        return work.run(() -> {
            access.require(OperatorPermission.BILLING_READ);
            ledger.salon(salonId, false).orElseThrow(NotFoundException::new);
            BillingLedger.Subscription subscription =
                    ledger.subscriptionOf(salonId, false).orElseThrow(NotFoundException::new);
            return view(subscription, clock.instant());
        });
    }

    /** The validated amount and method of a new subscription; field names carry the given prefix. */
    static void validateTerms(String prefix, Long amount, String method, List<FieldProblem> errors) {
        if (!BillingTerms.validAmount(amount)) {
            errors.add(new FieldProblem(
                    prefix + "monthlyAmount",
                    "must be a whole number of minor units from 0 to " + BillingTerms.MAX_AMOUNT));
        }
        if (!BillingTerms.validMethod(method)) {
            errors.add(new FieldProblem(prefix + "paymentMethod", "must be card or bank_transfer"));
        }
    }

    /** The result of a start: whether it replayed an earlier one, and the subscription. */
    public record Started(boolean replayed, SubscriptionView subscription) {}

    /**
     * Starts the subscription of an unbilled salon. A salon without a currency of its own (a legacy
     * salon) must be given one, stored on the subscription only; a salon with one must not.
     */
    public Started start(UUID salonId, String idempotencyKey, StartInput input) {
        access.check(OperatorPermission.BILLING_MANAGE);
        UUID key = ProvisioningReceipts.key(idempotencyKey);
        List<FieldProblem> basic = new ArrayList<>();
        validateTerms("", input.monthlyAmount(), input.paymentMethod(), basic);
        String currency =
                input.currency() == null ? null : input.currency().strip().toUpperCase(Locale.ROOT);
        if (currency != null && !ProvisioningRegions.CURRENCIES.contains(currency)) {
            basic.add(new FieldProblem("currency", "must be a supported ISO 4217 three-letter currency code"));
        }
        if (!basic.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, basic);
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("salonId", salonId.toString());
        request.put("monthlyAmount", input.monthlyAmount());
        request.put("paymentMethod", input.paymentMethod());
        request.put("currency", currency);
        String fingerprint = receipts.fingerprint(START, request);
        return work.run(() -> {
            var operator = access.require(OperatorPermission.BILLING_MANAGE);
            var earlier = receipts.replay(operator.id(), START, key, fingerprint);
            if (earlier.isPresent()) {
                return new Started(true, json.convertValue(earlier.get().result(), SubscriptionView.class));
            }
            BillingLedger.Salon salon = ledger.salon(salonId, true).orElseThrow(NotFoundException::new);
            stillBilled(salon);
            if (salon.currency() == null && currency == null) {
                throw new ApplicationProblemException(
                        400,
                        ErrorCode.BAD_REQUEST,
                        List.of(new FieldProblem("currency", "is required for a salon without a currency")));
            }
            if (salon.currency() != null && currency != null) {
                throw new ApplicationProblemException(
                        400,
                        ErrorCode.BAD_REQUEST,
                        List.of(new FieldProblem("currency", "is the salon's own currency and cannot be given")));
            }
            if (ledger.subscriptionOf(salonId, true).isPresent()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            BillingLedger.Subscription subscription = states.start(
                    salon,
                    input.monthlyAmount(),
                    salon.currency() != null ? salon.currency() : currency,
                    input.paymentMethod(),
                    now,
                    PlatformAudit.OPERATOR,
                    operator.id().toString());
            SubscriptionView view = view(subscription, now);
            // Jackson converts to a raw Map; a view is a JSON object, so every key is a string.
            @SuppressWarnings("unchecked")
            Map<String, Object> stored = json.convertValue(view, Map.class);
            receipts.store(operator.id(), START, key, fingerprint, subscription.id(), stored, now);
            return new Started(false, view);
        });
    }

    /** Changes the amount and/or method; a changed amount applies from the next invoice. */
    public SubscriptionView change(UUID salonId, ChangeInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        if (input.version() == null || input.version() < 0) {
            errors.add(new FieldProblem("version", "must be the subscription's current version"));
        }
        if (input.monthlyAmount() == null && input.paymentMethod() == null) {
            errors.add(new FieldProblem("monthlyAmount", "or paymentMethod must be given"));
        }
        if (input.monthlyAmount() != null && !BillingTerms.validAmount(input.monthlyAmount())) {
            errors.add(new FieldProblem(
                    "monthlyAmount", "must be a whole number of minor units from 0 to " + BillingTerms.MAX_AMOUNT));
        }
        if (input.paymentMethod() != null && !BillingTerms.validMethod(input.paymentMethod())) {
            errors.add(new FieldProblem("paymentMethod", "must be card or bank_transfer"));
        }
        return work.run(() -> {
            var operator = access.require(OperatorPermission.BILLING_MANAGE);
            if (!errors.isEmpty()) {
                throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
            }
            stillBilled(ledger.salon(salonId, true).orElseThrow(NotFoundException::new));
            BillingLedger.Subscription subscription =
                    ledger.subscriptionOf(salonId, true).orElseThrow(NotFoundException::new);
            if (subscription.version() != input.version()) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            long amount = input.monthlyAmount() == null ? subscription.monthlyAmount() : input.monthlyAmount();
            String method = input.paymentMethod() == null ? subscription.paymentMethod() : input.paymentMethod();
            Instant now = clock.instant();
            Map<String, Object> details = new LinkedHashMap<>();
            List<String> fields = new ArrayList<>();
            if (amount != subscription.monthlyAmount()) {
                fields.add("monthlyAmount");
                details.put("monthlyAmount", Map.of("before", subscription.monthlyAmount(), "after", amount));
            }
            if (!method.equals(subscription.paymentMethod())) {
                fields.add("paymentMethod");
                details.put("paymentMethod", Map.of("before", subscription.paymentMethod(), "after", method));
            }
            if (fields.isEmpty()) {
                return view(subscription, now);
            }
            details.put("fields", fields);
            database.jdbcTemplate().update("""
                    UPDATE subscription SET monthly_amount = ?, payment_method = ?, version = version + 1, updated_at = ?
                     WHERE id = ?
                    """, amount, method, java.sql.Timestamp.from(now), subscription.id());
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "subscription.changed",
                    "salon",
                    salonId.toString(),
                    details,
                    now);
            return view(ledger.subscription(subscription.id(), false).orElseThrow(), now);
        });
    }

    /**
     * Moves the salon to another plan. The salon's registry row is locked, so a staff creation in
     * progress (which share-locks it) finishes first and is counted. The next invoice carries the new
     * plan; issued invoices keep theirs.
     */
    public SalonDirectory.SalonDetail movePlan(UUID salonId, PlanMoveInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        if (input.version() == null || input.version() < 0) {
            errors.add(new FieldProblem("version", "must be the salon's current version"));
        }
        if (input.planId() == null) {
            errors.add(new FieldProblem("planId", "is required"));
        }
        work.run(() -> {
            var operator = access.require(OperatorPermission.BILLING_MANAGE);
            if (!errors.isEmpty()) {
                throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
            }
            BillingLedger.Salon salon = ledger.salon(salonId, true).orElseThrow(NotFoundException::new);
            stillBilled(salon);
            List<Integer> seats = database.jdbcTemplate()
                    .queryForList("SELECT staff_seats FROM plan WHERE id = ?", Integer.class, input.planId());
            if (seats.isEmpty()) {
                throw new NotFoundException();
            }
            if (salon.version() != input.version()) {
                throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            }
            if (input.planId().equals(salon.planId())) {
                return null;
            }
            long used = identity.activeStaffCount(salonId);
            if (used > seats.get(0)) {
                throw SalonEntitlements.exceeded(SalonEntitlements.STAFF_SEATS, seats.get(0));
            }
            Instant now = clock.instant();
            database.jdbcTemplate().update("""
                    UPDATE tenant SET plan_id = ?, version = version + 1, updated_at = ? WHERE id = ?
                    """, input.planId(), java.sql.Timestamp.from(now), salonId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put(
                    "fromPlanId", salon.planId() == null ? null : salon.planId().toString());
            details.put("toPlanId", input.planId().toString());
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "salon.plan_changed",
                    "salon",
                    salonId.toString(),
                    details,
                    now);
            return null;
        });
        return salons.get(salonId);
    }

    /** A closed or deleted salon's billing has stopped (F03-S03): no subscription change applies to it. */
    private static void stillBilled(BillingLedger.Salon salon) {
        if ("closed".equals(salon.status()) || "deleted".equals(salon.status())) {
            throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
        }
    }
}
