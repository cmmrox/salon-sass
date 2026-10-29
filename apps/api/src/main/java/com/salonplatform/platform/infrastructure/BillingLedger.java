package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.platform.domain.BillingCalendar.State;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Subscription billing's records on the platform connection (docs/architecture/operator-console.md,
 * "Subscriptions, invoices and entitlements"): salons and subscriptions as billing reads them, and the subscription and registry changes billing makes. Named direct-SQL exception 1 of
 * docs/engineering/backend.md: platform tables with no {@code tenant_id}. Every method runs inside the
 * caller's platform transaction, and a subscription is changed only while its row is locked. Invoices and
 * payments are {@link InvoiceStore}, the outbox messages billing writes {@link PlatformOutbox}.
 */
@Component
public class BillingLedger {

    public static final String EMAIL = "email";
    public static final String CHARGE = "billing-charge";

    /** A salon as billing needs it. */
    public record Salon(
            UUID id,
            String slug,
            String name,
            ZoneId zone,
            String currency,
            String ownerEmail,
            UUID planId,
            String planKey,
            String planName,
            String status,
            String accessState,
            long version) {}

    /** A subscription row. */
    public record Subscription(
            UUID id,
            UUID salonId,
            State state,
            long monthlyAmount,
            String currency,
            String paymentMethod,
            Instant startedAt,
            Instant trialEndsAt,
            LocalDateTime anchor,
            int invoicesIssued,
            Instant nextInvoiceAt,
            long version,
            Instant createdAt,
            Instant updatedAt) {}

    /** A new subscription: trialing, with its first invoice due at the end of the trial. */
    public record NewSubscription(
            UUID salonId,
            long monthlyAmount,
            String currency,
            String paymentMethod,
            Instant startedAt,
            Instant trialEndsAt,
            LocalDateTime anchor) {}

    /** The plan a salon is on: its key and name, limits and features. */
    public record SalonPlan(
            String key,
            String name,
            int staffSeats,
            int branches,
            int messageAllowance,
            boolean customDomain,
            boolean dedicatedApp) {}

    private final PlatformDatabase database;

    BillingLedger(PlatformDatabase database) {
        this.database = database;
    }

    // --- salons ----------------------------------------------------------------------------------

    /** A salon, optionally locking its registry row (plan moves). */
    public Optional<Salon> salon(UUID id, boolean lock) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("""
                        SELECT t.id, t.slug, t.name, t.time_zone, t.currency, t.owner_email, t.plan_id, t.status,
                               t.access_state, t.version, p.key AS plan_key, p.name AS plan_name
                          FROM tenant t LEFT JOIN plan p ON p.id = t.plan_id WHERE t.id = ?
                        """ + (lock ? " FOR UPDATE OF t" : ""))
                .param(id)
                .query((row, n) -> salon(row))
                .list()
                .stream()
                .findFirst();
    }

    /**
     * Whether a salon is still billed: active or suspended. A closed or deleted salon's billing stopped at
     * its closure (F03-S03). Read with a share lock on its registry row, so a closure waits for a billing
     * pass in progress and a pass that starts after it sees it.
     */
    public boolean billed(UUID salonId) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("SELECT status FROM tenant WHERE id = ? FOR SHARE")
                .param(salonId)
                .query(String.class)
                .list()
                .stream()
                .findFirst()
                .map(status -> !"closed".equals(status) && !"deleted".equals(status))
                .orElse(false);
    }

    /** The plan the salon is on, when it has one. */
    public Optional<SalonPlan> planOf(UUID salonId) {
        return database
                .jdbc()
                .sql("""
                        SELECT p.key, p.name, p.staff_seats, p.branches, p.message_allowance, p.custom_domain, p.dedicated_app
                          FROM tenant t JOIN plan p ON p.id = t.plan_id WHERE t.id = ?
                        """)
                .param(salonId)
                .query((row, n) -> new SalonPlan(
                        row.getString("key"),
                        row.getString("name"),
                        row.getInt("staff_seats"),
                        row.getInt("branches"),
                        row.getInt("message_allowance"),
                        row.getBoolean("custom_domain"),
                        row.getBoolean("dedicated_app")))
                .list()
                .stream()
                .findFirst();
    }

    /** Sets the registry's access state, when it is not that already. */
    public void setAccessState(UUID salonId, String access, Instant at) {
        database.jdbc()
                .sql("UPDATE tenant SET access_state = ?, updated_at = ? WHERE id = ? AND access_state <> ?")
                .params(access, time(at), salonId, access)
                .update();
    }

    /** Moves the salon to another plan (the caller holds its registry row's lock). */
    public void movePlan(UUID salonId, UUID planId, Instant at) {
        database.jdbc().sql("""
                        UPDATE tenant SET plan_id = ?, version = version + 1, updated_at = ? WHERE id = ?
                        """).params(planId, time(at), salonId).update();
    }

    // --- subscriptions ---------------------------------------------------------------------------

    public Optional<Subscription> subscriptionOf(UUID salonId, boolean lock) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("SELECT * FROM subscription WHERE salon_id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(salonId)
                .query((row, n) -> subscription(row))
                .list()
                .stream()
                .findFirst();
    }

    public Optional<Subscription> subscription(UUID id, boolean lock) {
        database.requireTransaction();
        return database
                .jdbc()
                .sql("SELECT * FROM subscription WHERE id = ?" + (lock ? " FOR UPDATE" : ""))
                .param(id)
                .query((row, n) -> subscription(row))
                .list()
                .stream()
                .findFirst();
    }

    /**
     * The subscriptions a billing pass has work for, of salons still billed: an invoice due to be issued,
     * an open invoice, or a state other than active or trialing.
     */
    public List<UUID> subscriptionsToBill(Instant now) {
        return database.jdbc().sql("""
                        SELECT s.id FROM subscription s JOIN tenant t ON t.id = s.salon_id
                         WHERE t.status NOT IN ('closed', 'deleted')
                           AND (s.next_invoice_at <= ?
                            OR EXISTS (SELECT 1 FROM invoice i WHERE i.subscription_id = s.id AND i.status = 'open')
                            OR s.state <> 'active' AND s.state <> 'trialing')
                         ORDER BY s.id
                        """).param(time(now)).query(UUID.class).list();
    }

    public void startSubscription(UUID id, NewSubscription subscription, Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO subscription (id, salon_id, state, monthly_amount, currency, payment_method, started_at,
                            trial_ends_at, anchor_local, invoices_issued, next_invoice_at, version, created_at, updated_at)
                        VALUES (?, ?, 'trialing', ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?)
                        """)
                .params(
                        id,
                        subscription.salonId(),
                        subscription.monthlyAmount(),
                        subscription.currency(),
                        subscription.paymentMethod(),
                        time(at),
                        time(subscription.trialEndsAt()),
                        Timestamp.valueOf(subscription.anchor()),
                        time(subscription.trialEndsAt()),
                        time(at),
                        time(at))
                .update();
    }

    public void changeState(UUID subscription, State state, Instant at) {
        database.jdbc()
                .sql("UPDATE subscription SET state = ?, updated_at = ? WHERE id = ?")
                .params(state.value(), time(at), subscription)
                .update();
    }

    /** Changes the amount and the payment method, from the next invoice on. */
    public void changeTerms(UUID subscription, long amount, String method, Instant at) {
        database.jdbc().sql("""
                        UPDATE subscription SET monthly_amount = ?, payment_method = ?, version = version + 1, updated_at = ?
                         WHERE id = ?
                        """).params(amount, method, time(at), subscription).update();
    }

    /** Records that the subscription's invoices up to {@code invoicesIssued} exist, and when the next is due. */
    public void recordIssued(UUID subscription, int invoicesIssued, Instant nextInvoiceAt, Instant at) {
        database.jdbc()
                .sql("""
                        UPDATE subscription SET invoices_issued = ?, next_invoice_at = ?, updated_at = ? WHERE id = ?
                        """)
                .params(invoicesIssued, time(nextInvoiceAt), time(at), subscription)
                .update();
    }

    // --- rows ------------------------------------------------------------------------------------

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Salon salon(ResultSet row) throws SQLException {
        return new Salon(
                row.getObject("id", UUID.class),
                row.getString("slug"),
                row.getString("name"),
                ZoneId.of(row.getString("time_zone")),
                row.getString("currency"),
                row.getString("owner_email"),
                row.getObject("plan_id", UUID.class),
                row.getString("plan_key"),
                row.getString("plan_name"),
                row.getString("status"),
                row.getString("access_state"),
                row.getLong("version"));
    }

    private static Subscription subscription(ResultSet row) throws SQLException {
        return new Subscription(
                row.getObject("id", UUID.class),
                row.getObject("salon_id", UUID.class),
                State.of(row.getString("state")),
                row.getLong("monthly_amount"),
                row.getString("currency"),
                row.getString("payment_method"),
                instant(row, "started_at"),
                instant(row, "trial_ends_at"),
                row.getTimestamp("anchor_local").toLocalDateTime(),
                row.getInt("invoices_issued"),
                instant(row, "next_invoice_at"),
                row.getLong("version"),
                instant(row, "created_at"),
                instant(row, "updated_at"));
    }
}
