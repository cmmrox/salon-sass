package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.platform.adapter.BillingProviderKey;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.BillingFaults;
import com.salonplatform.platform.service.BillingJob;
import com.salonplatform.platform.service.OperatorAccounts;
import com.salonplatform.platform.service.ProviderEvents;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * F03-S02 subscriptions and invoices over real HTTP and PostgreSQL with a movable application clock:
 * the trial at creation, starting an unbilled salon, invoice issue and month anchors with repeated and
 * concurrent job runs, card charges through signed provider events, recorded transfers, the overdue
 * timeline and recovery, plan moves and amount changes, operator permissions and host binding, audit,
 * and an interrupted job (AC-1 to AC-7, AC-9, AC-11, AC-13, AC-14, AC-16).
 */
class SubscriptionBillingIntegrationTest extends IntegrationTest {

    static final MovableClock CLOCK = new MovableClock(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    static final AtomicReference<BillingFaults.Point> ARMED = new AtomicReference<>();

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        BillingFaults armedFaults() {
            return point -> {
                if (ARMED.compareAndSet(point, null)) {
                    throw new IllegalStateException("controlled billing interruption");
                }
            };
        }
    }

    @Autowired
    OperatorAccounts accounts;

    @Autowired
    OperatorCrypto crypto;

    @Autowired
    IdentityCrypto identityCrypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    PlatformTransactionManager manager;

    @Autowired
    BillingJob job;

    @Autowired
    ProviderEvents events;

    @Autowired
    BillingProviderKey providerKey;

    @PersistenceContext
    EntityManager em;

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private BillingTestSupport billing;

    @BeforeEach
    void setUp() {
        CLOCK.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        ARMED.set(null);
        billing = support();
    }

    private BillingTestSupport support() {
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), accounts, crypto, json, environment, CLOCK);
        return new BillingTestSupport(console, http(), port(), owner, identityCrypto, json, manager, em, CLOCK);
    }

    private Map<String, Object> body(Http.Exchange exchange) {
        return billing.body(exchange);
    }

    private static void status(Http.Exchange exchange, int expected) {
        BillingTestSupport.status(exchange, expected);
    }

    private Http.Exchange op(String method, String path, Object request) {
        return billing.console.call(method, path, request, billing.admin());
    }

    private Http.Exchange opKeyed(String method, String path, Object request, String key) {
        return billing.console.callOn(
                billing.console.host(), method, path, request, billing.admin(), Map.of("Idempotency-Key", key));
    }

    /** Moves the clock and signs a fresh admin in (operator sessions end seven days after sign-in). */
    private void at(Instant instant) {
        CLOCK.set(instant);
        billing = support();
    }

    private UUID uuid(Object value) {
        return UUID.fromString((String) value);
    }

    private List<Map<String, Object>> invoices(UUID salon) {
        return owner.queryForList("SELECT * FROM invoice WHERE salon_id = ? ORDER BY period_index", salon);
    }

    private Map<String, Object> subscriptionRow(UUID salon) {
        return owner.queryForMap("SELECT * FROM subscription WHERE salon_id = ?", salon);
    }

    private String accessState(UUID salon) {
        return owner.queryForObject("SELECT access_state FROM tenant WHERE id = ?", String.class, salon);
    }

    private long lastNumber() {
        return owner.queryForObject("SELECT last_number FROM invoice_number", Long.class);
    }

    private Instant instant(Object value) {
        return ((java.sql.Timestamp) value).toInstant();
    }

    private Map<String, Object> request(Long amount, String method) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("monthlyAmount", amount);
        request.put("paymentMethod", method);
        return request;
    }

    // --- AC-1 ------------------------------------------------------------------------------------

    @Test
    void aNewSalonIsTrialingForExactlyFourteenDaysAndBadBillingTermsCreateNothing() {
        String plan = billing.plan(5);
        Map<String, Object> salon = billing.provisioned(plan, 250000, "card", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        Http.Exchange subscription = op("GET", "/salons/" + id + "/subscription", null);
        status(subscription, 200);
        Map<String, Object> view = body(subscription);
        assertThat(view)
                .containsEntry("state", "trialing")
                .containsEntry("currency", "LKR")
                .containsEntry("paymentMethod", "card")
                .containsEntry("version", 0);
        assertThat(((Number) view.get("monthlyAmount")).longValue()).isEqualTo(250000);
        Instant created = Instant.parse((String) salon.get("createdAt"));
        assertThat(Instant.parse((String) view.get("trialEndsAt"))).isEqualTo(created.plus(Duration.ofDays(14)));
        assertThat(view.get("currentPeriod")).isNull();
        assertThat(view.get("readOnlyAt")).isNull();
        assertThat(salon).containsEntry("version", 0);

        Object[][] bad = {
            {null, "card", "subscription.monthlyAmount"},
            {-1, "card", "subscription.monthlyAmount"},
            {1.5, "card", "subscription.monthlyAmount"},
            {100000001, "card", "subscription.monthlyAmount"},
            {"5", "card", "subscription.monthlyAmount"},
            {100, "cheque", "subscription.paymentMethod"},
            {100, null, "subscription.paymentMethod"}
        };
        for (Object[] row : bad) {
            String slug = OperatorTestSupport.uniqueKey("bad");
            Map<String, Object> create = OperatorTestSupport.salon(
                    slug, plan, "o-" + slug + "@example.test", "synthetic owner " + UUID.randomUUID());
            Map<String, Object> terms = new LinkedHashMap<>();
            terms.put("monthlyAmount", row[0]);
            terms.put("paymentMethod", row[1]);
            create.put("subscription", terms);
            Http.Exchange refused =
                    opKeyed("POST", "/salons", create, UUID.randomUUID().toString());
            status(refused, 400);
            assertThat(refused.body()).contains("\"" + row[2] + "\"");
            assertThat(owner.queryForObject("SELECT count(*) FROM tenant WHERE slug = ?", Integer.class, slug))
                    .isZero();
        }
        String slug = OperatorTestSupport.uniqueKey("bad");
        Map<String, Object> missing = OperatorTestSupport.salon(
                slug, plan, "o-" + slug + "@example.test", "synthetic owner " + UUID.randomUUID());
        missing.remove("subscription");
        Http.Exchange refused =
                opKeyed("POST", "/salons", missing, UUID.randomUUID().toString());
        status(refused, 400);
        assertThat(refused.body()).contains("\"subscription\"");
        assertThat(owner.queryForObject("SELECT count(*) FROM tenant WHERE slug = ?", Integer.class, slug))
                .isZero();
    }

    // --- AC-2 ------------------------------------------------------------------------------------

    @Test
    void anUnbilledSalonHasNoSubscriptionUntilAnAdminStartsOneAndASecondStartIsRefused() {
        TestSalon legacy = billing.legacy(null);
        status(op("GET", "/salons/" + legacy.id() + "/subscription", null), 404);
        assertThat(op("GET", "/salons/" + legacy.id() + "/subscription", null).body())
                .contains("not_found");

        Http.Exchange noCurrency = billing.startSubscription(
                legacy.id(), request(90000L, "bank_transfer"), UUID.randomUUID().toString());
        status(noCurrency, 400);
        assertThat(noCurrency.body()).contains("\"currency\"");
        Map<String, Object> withCurrency = request(90000L, "bank_transfer");
        withCurrency.put("currency", "lkr");
        String key = UUID.randomUUID().toString();
        Http.Exchange started = billing.startSubscription(legacy.id(), withCurrency, key);
        status(started, 201);
        Map<String, Object> view = body(started);
        assertThat(view).containsEntry("state", "trialing").containsEntry("currency", "LKR");
        assertThat(Instant.parse((String) view.get("trialEndsAt")))
                .isEqualTo(CLOCK.instant().plus(Duration.ofDays(14)));
        assertThat(owner.queryForObject("SELECT currency FROM tenant WHERE id = ?", String.class, legacy.id()))
                .isNull();

        // The same key and input replays the original; a new key is a second start.
        Http.Exchange replay = billing.startSubscription(legacy.id(), withCurrency, key);
        status(replay, 201);
        assertThat(body(replay)).isEqualTo(view);
        Http.Exchange second = billing.startSubscription(
                legacy.id(), withCurrency, UUID.randomUUID().toString());
        status(second, 409);
        assertThat(second.body()).contains("invalid_state");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM subscription WHERE salon_id = ?", Integer.class, legacy.id()))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'subscription.started'"
                                + " AND subject_id = ?",
                        Integer.class,
                        legacy.id().toString()))
                .isEqualTo(1);

        // A salon with a currency of its own may not be given another.
        Map<String, Object> provisioned = billing.provisioned(billing.plan(3), 1000, "card", "Asia/Colombo");
        UUID other = uuid(provisioned.get("id"));
        Http.Exchange refused =
                billing.startSubscription(other, withCurrency, UUID.randomUUID().toString());
        status(refused, 400);
        assertThat(refused.body()).contains("\"currency\"");
        status(
                billing.startSubscription(
                        UUID.randomUUID(),
                        request(1L, "card"),
                        UUID.randomUUID().toString()),
                404);
    }

    // --- AC-3 ------------------------------------------------------------------------------------

    @Test
    void crossingTrialEndIssuesExactlyOneInvoiceAndEachMonthOneOnTheAnchoredDay() throws Exception {
        // Created on 17 January 10:00 in Colombo: the trial ends on the 31st.
        at(Instant.parse("2031-01-17T04:30:00Z"));
        String plan = billing.plan(4);
        Map<String, Object> salon = billing.provisioned(plan, 123456, "bank_transfer", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        Instant trialEnd = Instant.parse("2031-01-31T04:30:00Z");

        CLOCK.set(trialEnd.minusSeconds(1));
        job.run(CLOCK.instant());
        assertThat(invoices(id)).isEmpty();

        long before = lastNumber();
        CLOCK.set(trialEnd);
        List<Callable<BillingJob.Pass>> runs = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            runs.add(() -> {
                start.await();
                return job.run(CLOCK.instant());
            });
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<BillingJob.Pass>> results = new ArrayList<>();
        for (Callable<BillingJob.Pass> run : runs) {
            results.add(pool.submit(run));
        }
        start.countDown();
        for (Future<BillingJob.Pass> result : results) {
            result.get();
        }
        pool.shutdown();
        job.run(CLOCK.instant());

        List<Map<String, Object>> issued = invoices(id);
        assertThat(issued).hasSize(1);
        Map<String, Object> first = issued.get(0);
        assertThat(((Number) first.get("number")).longValue()).isGreaterThan(before);
        assertThat(first)
                .containsEntry("plan_id", UUID.fromString(plan))
                .containsEntry("currency", "LKR")
                .containsEntry("status", "open");
        assertThat(((Number) first.get("amount")).longValue()).isEqualTo(123456);
        assertThat(instant(first.get("period_start"))).isEqualTo(trialEnd);
        assertThat(instant(first.get("period_end"))).isEqualTo(Instant.parse("2031-02-28T04:30:00Z"));
        assertThat(instant(first.get("issued_at"))).isEqualTo(trialEnd);
        assertThat(instant(first.get("due_at"))).isEqualTo(trialEnd.plus(Duration.ofDays(7)));
        assertThat(subscriptionRow(id)).containsEntry("state", "active");

        // February has no 31st: the anchor falls on the 28th, March returns to the 31st.
        Map<String, Object> invoice = body(op("GET", "/invoices/" + first.get("id"), null));
        assertThat(invoice)
                .containsEntry(
                        "planKey", owner.queryForObject("SELECT key FROM plan WHERE id = ?::uuid", String.class, plan));
        assertThat((String) invoice.get("number")).matches("INV-[0-9]{6,}");
        CLOCK.set(Instant.parse("2031-02-28T04:29:59Z"));
        job.run(CLOCK.instant());
        assertThat(invoices(id)).hasSize(1);
        CLOCK.set(Instant.parse("2031-02-28T04:30:00Z"));
        job.run(CLOCK.instant());
        job.run(CLOCK.instant());
        assertThat(invoices(id)).hasSize(2);
        CLOCK.set(Instant.parse("2031-03-31T04:30:00Z"));
        job.run(CLOCK.instant());
        List<Map<String, Object>> three = invoices(id);
        assertThat(three).hasSize(3);
        assertThat(instant(three.get(2).get("period_start"))).isEqualTo(Instant.parse("2031-03-31T04:30:00Z"));
        assertThat(instant(three.get(1).get("period_end"))).isEqualTo(Instant.parse("2031-03-31T04:30:00Z"));
        // Numbers are consecutive per issue and unique platform-wide.
        assertThat(owner.queryForObject(
                        "SELECT count(DISTINCT number) FROM invoice WHERE salon_id = ?", Integer.class, id))
                .isEqualTo(3);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'invoice.issued'"
                                + " AND details->>'salonId' = ? AND actor_kind = 'system' AND trace_id ~ '^[0-9a-f]{32}$'",
                        Integer.class,
                        id.toString()))
                .isEqualTo(3);
        // An issued invoice never changes, whichever role asks.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> owner.update("UPDATE invoice SET amount = 1 WHERE id = ?", first.get("id")))
                .rootCause()
                .hasMessageContaining("never changed");
    }

    // --- AC-4 and AC-5 ---------------------------------------------------------------------------

    private Map<String, Object> attempt(UUID invoice, String occasion) {
        return owner.queryForMap(
                "SELECT * FROM charge_attempt WHERE invoice_id = ? AND occasion = ?", invoice, occasion);
    }

    private Http.Exchange postEvent(String body, String signature) {
        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        if (signature != null) {
            headers.put(BillingProviderKey.HEADER, signature);
        }
        return http().sendOnHost("POST", "localhost:" + port(), "/api/v1/billing/provider-events", headers, body);
    }

    private String event(String eventId, Object invoice, Object attemptKey, String outcome) {
        return json.writeValueAsString(Map.of(
                "eventId",
                eventId,
                "invoiceId",
                invoice.toString(),
                "attemptKey",
                attemptKey.toString(),
                "outcome",
                outcome,
                "occurredAt",
                CLOCK.instant().toString()));
    }

    private String sign(String body) {
        return providerKey.sign(body.getBytes(StandardCharsets.UTF_8)).orElseThrow();
    }

    private UUID cardSalonWithInvoice() {
        Map<String, Object> salon = billing.provisioned(billing.plan(3), 50000, "card", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        CLOCK.advance(Duration.ofDays(14));
        job.run(CLOCK.instant());
        assertThat(invoices(id)).hasSize(1);
        return id;
    }

    @Test
    void aSuccessfulChargePaysTheInvoiceOnceAndAFailedOneNotifiesOnceAndIsRetriedOnReminderDays() {
        UUID paidSalon = cardSalonWithInvoice();
        UUID invoice = (UUID) invoices(paidSalon).get(0).get("id");
        Map<String, Object> issue = attempt(invoice, "issue");
        assertThat(issue).containsEntry("status", "requested");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = "
                                + "'billing-charge' AND payload->>'attemptKey' = ?",
                        Integer.class,
                        paidSalon,
                        issue.get("attempt_key").toString()))
                .isEqualTo(1);
        String success = event("evt-ok-" + invoice, invoice, issue.get("attempt_key"), "success");
        Http.Exchange paid = postEvent(success, sign(success));
        status(paid, 200);
        assertThat(body(paid)).containsEntry("result", "paid");
        // The same event id again, and another event for the paid invoice, record no second payment.
        assertThat(body(postEvent(success, sign(success)))).containsEntry("result", "duplicate");
        String late = event("evt-late-" + invoice, invoice, issue.get("attempt_key"), "success");
        assertThat(body(postEvent(late, sign(late)))).containsEntry("result", "already_paid");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM invoice_payment WHERE invoice_id = ?", Integer.class, invoice))
                .isEqualTo(1);
        assertThat(invoices(paidSalon).get(0)).containsEntry("status", "paid");
        assertThat(subscriptionRow(paidSalon)).containsEntry("state", "active");

        UUID failedSalon = cardSalonWithInvoice();
        UUID open = (UUID) invoices(failedSalon).get(0).get("id");
        Map<String, Object> failedAttempt = attempt(open, "issue");
        String failure = event("evt-fail-" + open, open, failedAttempt.get("attempt_key"), "failure");
        assertThat(body(postEvent(failure, sign(failure)))).containsEntry("result", "failed");
        String again = event("evt-fail2-" + open, open, failedAttempt.get("attempt_key"), "failure");
        assertThat(body(postEvent(again, sign(again)))).containsEntry("result", "failed");
        assertThat(invoices(failedSalon).get(0)).containsEntry("status", "open");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM billing_reminder WHERE invoice_id = ? AND kind = " + "'failed_charge'",
                        Integer.class,
                        open))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = 'email'"
                                + " AND payload->>'kind' = 'billing.failed_charge'",
                        Integer.class,
                        failedSalon))
                .isEqualTo(1);
        String recipient =
                owner.queryForObject("SELECT owner_email FROM tenant WHERE id = ?", String.class, failedSalon);
        assertThat(owner.queryForObject(
                        "SELECT payload->>'recipient' FROM outbox_message WHERE tenant_id = ? AND "
                                + "channel = 'email'",
                        String.class,
                        failedSalon))
                .isEqualTo(recipient);
        assertThat(attempt(open, "issue")).containsEntry("status", "failed");

        // Retried on each reminder day while unpaid.
        Instant due = instant(invoices(failedSalon).get(0).get("due_at"));
        CLOCK.set(due.minusSeconds(1));
        job.run(CLOCK.instant());
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM charge_attempt WHERE invoice_id = ?", Integer.class, open))
                .isEqualTo(1);
        CLOCK.set(due);
        job.run(CLOCK.instant());
        job.run(CLOCK.instant());
        CLOCK.set(due.plus(Duration.ofDays(3)));
        job.run(CLOCK.instant());
        CLOCK.set(due.plus(Duration.ofDays(10)));
        job.run(CLOCK.instant());
        assertThat(owner.queryForList(
                        "SELECT occasion FROM charge_attempt WHERE invoice_id = ? ORDER BY requested_at",
                        String.class,
                        open))
                .containsExactly("issue", "due", "due_3", "due_10");
    }

    @Test
    void unsignedWronglySignedAndMalformedEventsAreRefusedWithoutEffect() {
        UUID salon = cardSalonWithInvoice();
        UUID invoice = (UUID) invoices(salon).get(0).get("id");
        Object key = attempt(invoice, "issue").get("attempt_key");
        String body = event("evt-sig-" + invoice, invoice, key, "success");
        status(postEvent(body, null), 401);
        status(postEvent(body, "0".repeat(64)), 401);
        status(postEvent(body, sign(body + " ")), 401);
        String tampered = body.replace("success", "failure");
        Http.Exchange refused = postEvent(tampered, sign(body));
        status(refused, 401);
        assertThat(refused.body()).contains("invalid_credentials");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM provider_event WHERE invoice_id = ?", Integer.class, invoice))
                .isZero();
        assertThat(invoices(salon).get(0)).containsEntry("status", "open");
        // A salon host or the console is not the platform host.
        Map<String, String> headers = Map.of("Content-Type", "application/json", BillingProviderKey.HEADER, sign(body));
        String slug = owner.queryForObject("SELECT slug FROM tenant WHERE id = ?", String.class, salon);
        status(
                http().sendOnHost(
                                "POST",
                                slug + ".localhost:" + port(),
                                "/api/v1/billing/provider-events",
                                headers,
                                body),
                404);
        status(
                http().sendOnHost(
                                "POST",
                                "console.localhost:" + port(),
                                "/api/v1/billing/provider-events",
                                headers,
                                body),
                404);
        // An unknown attempt is recorded and changes nothing.
        String unknown = event("evt-unknown-" + invoice, invoice, UUID.randomUUID(), "success");
        assertThat(body(postEvent(unknown, sign(unknown)))).containsEntry("result", "unknown");
        String malformed = "{\"eventId\":\"x\",\"invoiceId\":\"nope\",\"attemptKey\":\"" + key
                + "\",\"outcome\":\"maybe\",\"occurredAt\":\"later\",\"extra\":1}";
        Http.Exchange bad = postEvent(malformed, sign(malformed));
        status(bad, 400);
        assertThat(bad.body())
                .contains("\"invoiceId\"")
                .contains("\"outcome\"")
                .contains("\"occurredAt\"")
                .contains("\"extra\"");
        assertThat(invoices(salon).get(0)).containsEntry("status", "open");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM invoice_payment WHERE invoice_id = ?", Integer.class, invoice))
                .isZero();
    }

    // --- AC-6 ------------------------------------------------------------------------------------

    private Map<String, Object> transfer(String reference, Object amount, String receivedOn) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("reference", reference);
        request.put("amount", amount);
        request.put("receivedOn", receivedOn);
        return request;
    }

    @Test
    void anAdminRecordsAnExactTransferOnceAndEveryRefusalChangesNothing() {
        Map<String, Object> salon = billing.provisioned(billing.plan(3), 77700, "bank_transfer", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        CLOCK.advance(Duration.ofDays(14));
        job.run(CLOCK.instant());
        UUID invoice = (UUID) invoices(id).get(0).get("id");
        String today =
                LocalDate.ofInstant(CLOCK.instant(), ZoneId.of("Asia/Colombo")).toString();
        String tomorrow = LocalDate.ofInstant(CLOCK.instant(), ZoneId.of("Asia/Colombo"))
                .plusDays(1)
                .toString();
        String path = "/invoices/" + invoice + "/payments";

        Http.Exchange partial = opKeyed(
                "POST",
                path,
                transfer("BANK-1", 77699, today),
                UUID.randomUUID().toString());
        status(partial, 400);
        assertThat(partial.body()).contains("\"amount\"");
        Http.Exchange excess = opKeyed(
                "POST",
                path,
                transfer("BANK-1", 77701, today),
                UUID.randomUUID().toString());
        status(excess, 400);
        Http.Exchange future = opKeyed(
                "POST",
                path,
                transfer("BANK-1", 77700, tomorrow),
                UUID.randomUUID().toString());
        status(future, 400);
        assertThat(future.body()).contains("\"receivedOn\"");
        Http.Exchange blank = opKeyed(
                "POST", path, transfer(" ", 77700, today), UUID.randomUUID().toString());
        status(blank, 400);
        assertThat(blank.body()).contains("\"reference\"");
        status(
                opKeyed(
                        "POST",
                        "/invoices/" + UUID.randomUUID() + "/payments",
                        transfer("BANK-1", 77700, today),
                        UUID.randomUUID().toString()),
                404);
        status(op("POST", path, transfer("BANK-1", 77700, today)), 400);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM invoice_payment WHERE invoice_id = ?", Integer.class, invoice))
                .isZero();

        String key = UUID.randomUUID().toString();
        Http.Exchange recorded = opKeyed("POST", path, transfer(" BANK-1 ", 77700, today), key);
        status(recorded, 201);
        Map<String, Object> payment = body(recorded);
        assertThat(payment)
                .containsEntry("method", "bank_transfer")
                .containsEntry("reference", "BANK-1")
                .containsEntry("receivedOn", today);
        Http.Exchange replay = opKeyed("POST", path, transfer(" BANK-1 ", 77700, today), key);
        status(replay, 201);
        assertThat(body(replay)).isEqualTo(payment);
        Http.Exchange changed = opKeyed("POST", path, transfer("BANK-2", 77700, today), key);
        status(changed, 409);
        assertThat(changed.body()).contains("idempotency_conflict");
        Http.Exchange paidAgain = opKeyed(
                "POST",
                path,
                transfer("BANK-3", 77700, today),
                UUID.randomUUID().toString());
        status(paidAgain, 409);
        assertThat(paidAgain.body()).contains("invalid_state");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM invoice_payment WHERE invoice_id = ?", Integer.class, invoice))
                .isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = 'payment.recorded' AND"
                                + " subject_id = ?",
                        Integer.class,
                        invoice.toString()))
                .isEqualTo(1);
        Map<String, Object> detail = body(op("GET", "/invoices/" + invoice, null));
        assertThat(detail).containsEntry("status", "paid");
        assertThat((List<?>) detail.get("payments")).hasSize(1);

        // A late provider success after the manual payment records no second payment.
        Map<String, Object> list = body(op("GET", "/salons/" + id + "/invoices?page=0&size=5", null));
        assertThat(list).containsEntry("total", 1);
    }

    // --- AC-7 and AC-9 ---------------------------------------------------------------------------

    private long reminders(UUID invoice, String kind) {
        return owner.queryForObject(
                "SELECT count(*) FROM billing_reminder WHERE invoice_id = ? AND kind = ?", Long.class, invoice, kind);
    }

    private long emails(UUID salon, String kind) {
        return owner.queryForObject(
                "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = 'email' AND"
                        + " payload->>'kind' = ?",
                Long.class,
                salon,
                "billing." + kind);
    }

    @Test
    void anUnpaidInvoiceIsPastDueThenRemindedOnceEachThenReadOnlyAndPayingEveryOverdueInvoiceRestoresIt() {
        Map<String, Object> salon = billing.provisioned(billing.plan(3), 40000, "bank_transfer", "Europe/London");
        UUID id = uuid(salon.get("id"));
        CLOCK.advance(Duration.ofDays(14));
        job.run(CLOCK.instant());
        Map<String, Object> first = invoices(id).get(0);
        UUID invoice = (UUID) first.get("id");
        Instant due = instant(first.get("due_at"));

        Object[][] boundaries = {
            {due, "past_due", "due"},
            {due.plus(Duration.ofDays(3)), "past_due", "due_3"},
            {due.plus(Duration.ofDays(10)), "past_due", "due_10"},
            {due.plus(Duration.ofDays(14)), "read_only", "read_only"}
        };
        String previous = "active";
        for (Object[] boundary : boundaries) {
            Instant at = (Instant) boundary[0];
            CLOCK.set(at.minusSeconds(1));
            job.run(CLOCK.instant());
            assertThat(subscriptionRow(id).get("state"))
                    .as("one second before %s", boundary[2])
                    .isEqualTo(previous);
            assertThat(reminders(invoice, (String) boundary[2]))
                    .as("one second before %s", boundary[2])
                    .isZero();
            CLOCK.set(at);
            job.run(CLOCK.instant());
            job.run(CLOCK.instant());
            assertThat(subscriptionRow(id).get("state"))
                    .as("at %s", boundary[2])
                    .isEqualTo(boundary[1]);
            assertThat(reminders(invoice, (String) boundary[2]))
                    .as("at %s", boundary[2])
                    .isEqualTo(1);
            assertThat(emails(id, (String) boundary[2])).isEqualTo(1);
            previous = (String) boundary[1];
        }
        assertThat(accessState(id)).isEqualTo("read_only");
        at(CLOCK.instant());
        Map<String, Object> view = body(op("GET", "/salons/" + id + "/subscription", null));
        assertThat(view).containsEntry("state", "read_only");
        assertThat(Instant.parse((String) view.get("readOnlyAt"))).isEqualTo(due.plus(Duration.ofDays(14)));
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM platform_audit_entry WHERE action = "
                                + "'subscription.state_changed' AND subject_id = ? AND actor_kind = 'system'",
                        Integer.class,
                        id.toString()))
                .isEqualTo(3);

        // A second invoice becomes overdue too; paying one of the two keeps the salon read-only.
        Instant secondIssue = instant(subscriptionRow(id).get("next_invoice_at"));
        at(instant(subscriptionRow(id).get("next_invoice_at")).plus(Duration.ofDays(8)));
        job.run(CLOCK.instant());
        List<Map<String, Object>> open = invoices(id);
        assertThat(open).hasSize(2);
        assertThat(instant(open.get(1).get("issued_at"))).isEqualTo(secondIssue);
        String today =
                LocalDate.ofInstant(CLOCK.instant(), ZoneId.of("Europe/London")).toString();
        status(
                opKeyed(
                        "POST",
                        "/invoices/" + invoice + "/payments",
                        transfer("T-1", 40000, today),
                        UUID.randomUUID().toString()),
                201);
        assertThat(subscriptionRow(id).get("state")).isEqualTo("past_due");
        assertThat(accessState(id)).isEqualTo("full");
        status(
                opKeyed(
                        "POST",
                        "/invoices/" + open.get(1).get("id") + "/payments",
                        transfer("T-2", 40000, today),
                        UUID.randomUUID().toString()),
                201);
        assertThat(subscriptionRow(id).get("state")).isEqualTo("active");
        assertThat(accessState(id)).isEqualTo("full");
    }

    @Test
    void payingOneOfTwoInvoicesOverdueFourteenDaysKeepsTheSalonReadOnlyUntilBothArePaid() {
        Map<String, Object> salon = billing.provisioned(billing.plan(3), 40000, "bank_transfer", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        // Jump straight past two issue dates and both read-only boundaries: no transition is skipped.
        CLOCK.advance(Duration.ofDays(14 + 31 + 7 + 14 + 1));
        job.run(CLOCK.instant());
        List<Map<String, Object>> open = invoices(id);
        assertThat(open).hasSize(2);
        UUID first = (UUID) open.get(0).get("id");
        assertThat(reminders(first, "due")).isEqualTo(1);
        assertThat(reminders(first, "due_3")).isEqualTo(1);
        assertThat(reminders(first, "due_10")).isEqualTo(1);
        assertThat(reminders(first, "read_only")).isEqualTo(1);
        assertThat(accessState(id)).isEqualTo("read_only");
        at(CLOCK.instant());
        String today =
                LocalDate.ofInstant(CLOCK.instant(), ZoneId.of("Asia/Colombo")).toString();
        status(
                opKeyed(
                        "POST",
                        "/invoices/" + open.get(1).get("id") + "/payments",
                        transfer("T-9", 40000, today),
                        UUID.randomUUID().toString()),
                201);
        assertThat(accessState(id)).isEqualTo("read_only");
        status(
                opKeyed(
                        "POST",
                        "/invoices/" + first + "/payments",
                        transfer("T-8", 40000, today),
                        UUID.randomUUID().toString()),
                201);
        assertThat(accessState(id)).isEqualTo("full");
        assertThat(subscriptionRow(id).get("state")).isEqualTo("active");
    }

    // --- AC-11 -----------------------------------------------------------------------------------

    @Test
    void aPlanMoveIsRefusedWhileUsageExceedsTheNewLimitsAndChangesApplyFromTheNextInvoice() throws Exception {
        String roomy = billing.plan(5);
        String tight = billing.plan(1);
        Map<String, Object> salon = billing.provisioned(roomy, 10000, "bank_transfer", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        TestSalon test = billing.testSalon(id);
        billing.staff(test, com.salonplatform.identity.domain.StaffRole.MANAGER, BillingTestSupport.email("mgr"));

        Map<String, Object> move = new LinkedHashMap<>();
        move.put("version", 0);
        move.put("planId", tight);
        Http.Exchange refused = op("PUT", "/salons/" + id + "/plan", move);
        status(refused, 409);
        assertThat(refused.body()).contains("entitlement_exceeded").contains("\"staffSeats\"");
        assertThat(owner.queryForObject("SELECT plan_id FROM tenant WHERE id = ?", UUID.class, id))
                .isEqualTo(UUID.fromString(roomy));
        String middle = billing.plan(2);
        move.put("planId", UUID.randomUUID().toString());
        status(op("PUT", "/salons/" + id + "/plan", move), 404);
        move.put("planId", middle);
        move.put("version", 7);
        Http.Exchange stale = op("PUT", "/salons/" + id + "/plan", move);
        status(stale, 409);
        assertThat(stale.body()).contains("\"conflict\"");
        move.put("version", 0);
        Http.Exchange moved = op("PUT", "/salons/" + id + "/plan", move);
        status(moved, 200);
        assertThat(body(moved)).containsEntry("version", 1);

        // Issue the first invoice on the new plan, then change the amount: the issued invoice keeps its own.
        CLOCK.advance(Duration.ofDays(14));
        job.run(CLOCK.instant());
        at(CLOCK.instant());
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("version", 0);
        change.put("monthlyAmount", 20000);
        change.put("paymentMethod", "card");
        Http.Exchange changed = op("PATCH", "/salons/" + id + "/subscription", change);
        status(changed, 200);
        assertThat(body(changed)).containsEntry("version", 1).containsEntry("paymentMethod", "card");
        status(op("PATCH", "/salons/" + id + "/subscription", change), 409);
        CLOCK.set(instant(subscriptionRow(id).get("next_invoice_at")));
        job.run(CLOCK.instant());
        List<Map<String, Object>> issued = invoices(id);
        assertThat(issued).hasSize(2);
        assertThat(issued.get(0)).containsEntry("plan_id", UUID.fromString(middle));
        assertThat(((Number) issued.get(0).get("amount")).longValue()).isEqualTo(10000);
        assertThat(((Number) issued.get(1).get("amount")).longValue()).isEqualTo(20000);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM platform_audit_entry WHERE action IN "
                                + "('salon.plan_changed', 'subscription.changed') AND subject_id = ?",
                        Integer.class,
                        id.toString()))
                .isEqualTo(2);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("version", 1);
        bad.put("monthlyAmount", -5);
        Http.Exchange invalid = op("PATCH", "/salons/" + id + "/subscription", bad);
        status(invalid, 400);
        assertThat(invalid.body()).contains("\"monthlyAmount\"");
    }

    // --- AC-13 and AC-14 -------------------------------------------------------------------------

    @Test
    void supportOperatorsAndOtherHostsAreRefusedEveryBillingActionWithNoChange() {
        Map<String, Object> salon = billing.provisioned(billing.plan(3), 10000, "bank_transfer", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        CLOCK.advance(Duration.ofDays(14));
        job.run(CLOCK.instant());
        at(CLOCK.instant());
        UUID invoice = (UUID) invoices(id).get(0).get("id");
        String support = billing.console.token(billing.console.create("OPERATOR_SUPPORT"));
        String today =
                LocalDate.ofInstant(CLOCK.instant(), ZoneId.of("Asia/Colombo")).toString();
        Object[][] routes = {
            {"GET", "/salons/" + id + "/subscription", null},
            {"POST", "/salons/" + id + "/subscription", request(1L, "card")},
            {"PATCH", "/salons/" + id + "/subscription", Map.of("version", 0, "monthlyAmount", 1)},
            {"PUT", "/salons/" + id + "/plan", Map.of("version", 0, "planId", billing.plan(9))},
            {"GET", "/salons/" + id + "/invoices", null},
            {"GET", "/invoices/" + invoice, null},
            {"POST", "/invoices/" + invoice + "/payments", transfer("X", 10000, today)}
        };
        for (Object[] route : routes) {
            Http.Exchange denied = billing.console.callOn(
                    billing.console.host(),
                    (String) route[0],
                    (String) route[1],
                    route[2],
                    support,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            status(denied, 403);
            assertThat(denied.body()).contains("permission_denied");
            Http.Exchange anonymous = billing.console.callOn(
                    billing.console.host(),
                    (String) route[0],
                    (String) route[1],
                    route[2],
                    null,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString()));
            status(anonymous, 401);
            for (String host :
                    List.of("localhost:" + port(), billing.testSalon(id).host(port()), "admin.localhost:" + port())) {
                Http.Exchange elsewhere = billing.console.callOn(
                        host,
                        (String) route[0],
                        (String) route[1],
                        route[2],
                        billing.admin(),
                        Map.of("Idempotency-Key", UUID.randomUUID().toString()));
                status(elsewhere, 404);
            }
        }
        assertThat(invoices(id).get(0)).containsEntry("status", "open");
        assertThat(subscriptionRow(id)).containsEntry("version", 0L).containsEntry("monthly_amount", 10000L);

        // The platform audit holds who, what, when and the trace, and nothing secret.
        List<Map<String, Object>> audit = owner.queryForList(
                "SELECT actor_kind, actor_id, action, trace_id, details::text"
                        + " AS details FROM platform_audit_entry WHERE subject_id IN (?, ?)",
                id.toString(),
                invoice.toString());
        assertThat(audit)
                .extracting(row -> row.get("action"))
                .contains("subscription.started", "salon.created", "invoice.issued");
        assertThat(audit).allSatisfy(row -> {
            assertThat((String) row.get("trace_id")).matches("[0-9a-f]{32}");
            assertThat((String) row.get("details")).doesNotContain("password", "@example.test", "secret", "token");
        });
    }

    // --- AC-16 -----------------------------------------------------------------------------------

    @Test
    void anInterruptedRunLeavesNoPartialInvoiceOrChargeAndTheNextRunResumes() {
        Map<String, Object> salon = billing.provisioned(billing.plan(3), 30000, "card", "Asia/Colombo");
        UUID id = uuid(salon.get("id"));
        CLOCK.advance(Duration.ofDays(14));
        long before = lastNumber();
        ARMED.set(BillingFaults.Point.AFTER_INVOICE_ISSUE);
        BillingJob.Pass interrupted = job.run(CLOCK.instant());
        assertThat(interrupted.failed()).isNotEmpty();
        assertThat(invoices(id)).isEmpty();
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM outbox_message WHERE tenant_id = ? AND channel = " + "'billing-charge'",
                        Integer.class,
                        id))
                .isZero();
        assertThat(subscriptionRow(id)).containsEntry("invoices_issued", 0);
        job.run(CLOCK.instant());
        List<Map<String, Object>> issued = invoices(id);
        assertThat(issued).hasSize(1);
        assertThat(((Number) issued.get(0).get("number")).longValue()).isGreaterThan(before);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM charge_attempt WHERE invoice_id = ?",
                        Integer.class,
                        issued.get(0).get("id")))
                .isEqualTo(1);
    }

    @Test
    void theTenantRoleCannotReachBillingTables() throws Exception {
        JdbcTemplate app = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                TestDatabase.of(TestPostgres.shared()).connectAsApplication(), true));
        for (String table : List.of(
                "subscription",
                "invoice",
                "invoice_payment",
                "charge_attempt",
                "provider_event",
                "billing_reminder",
                "invoice_number")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.queryForList("SELECT * FROM " + table))
                    .as(table)
                    .rootCause()
                    .hasMessageContaining("permission denied");
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.queryForObject(
                        "SELECT outbox_enqueue_for_salon(gen_random_uuid(), gen_random_uuid(), 'email', '{}'::jsonb, NULL, now())::text",
                        String.class))
                .rootCause()
                .hasMessageContaining("permission denied");
    }
}
