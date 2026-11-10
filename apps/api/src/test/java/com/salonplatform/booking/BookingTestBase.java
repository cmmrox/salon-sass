package com.salonplatform.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.MovableClock;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * What F07-S01's endpoint tests share, in one application context: the application's clock standing still at 08:00 on
 * 15 June 2086 in Colombo until a test moves it, a reference source a test can script, and two salons prepared to take
 * bookings, each answer held to the committed contract. Each salon has saved booking rules, is open 08:00 to 20:00 every
 * day, and has one service, "Cut and finish" (45 minutes and a 15-minute buffer, Rs. 3,500), performed by two team
 * members, Nimali and Kasun, whose weeks are copied from those hours. Tomorrow, the 16th, is the usual booking date.
 */
@Import(BookingTestBase.Overrides.class)
abstract class BookingTestBase extends IntegrationTest {

    static final Instant EIGHT_AM = Instant.parse("2086-06-15T02:30:00Z");
    static final MovableClock CLOCK = new MovableClock(EIGHT_AM);
    static final ScriptedRandom RANDOM = new ScriptedRandom();
    static final String TOMORROW = "2086-06-16";
    static final String BOOKINGS = "/api/v1/bookings";

    @TestConfiguration
    static class Overrides {
        @Bean
        @Primary
        Clock movableClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        RandomGenerator scriptedReferences() {
            return RANDOM;
        }
    }

    /** Random, except for the draws a test queues. */
    static final class ScriptedRandom implements RandomGenerator {

        private final RandomGenerator random = new SecureRandom();
        private final Deque<Integer> queued = new ConcurrentLinkedDeque<>();

        /** The next draws of a reference's characters will spell this one. */
        void next(String reference) {
            for (char character : reference.toCharArray()) {
                queued.add(com.salonplatform.booking.domain.BookingReference.ALPHABET.indexOf(character));
            }
        }

        void clear() {
            queued.clear();
        }

        @Override
        public long nextLong() {
            return random.nextLong();
        }

        @Override
        public int nextInt(int bound) {
            Integer scripted = queued.poll();
            return scripted != null ? scripted : random.nextInt(bound);
        }
    }

    /** A salon ready to take bookings. */
    record Salon(TestSalon salon, String owner, UUID category, UUID service, UUID nimali, UUID kasun) {}

    @Autowired
    IdentityCrypto identityCrypto;

    @Autowired
    OperatorAccountService operators;

    @Autowired
    OperatorCrypto operatorCrypto;

    @Autowired
    JsonMapper json;

    @Autowired
    Environment environment;

    @Autowired
    PlatformTransactionManager transactions;

    @PersistenceContext
    EntityManager entities;

    final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    final OpenApiContract contract = OpenApiContract.committed();
    TestSalons salons;
    SettingsTestSupport settings;
    Salon alpha;
    Salon beta;
    private final Map<String, String> tokens = new HashMap<>();

    @BeforeEach
    void twoSalonsReadyToBook() {
        CLOCK.set(EIGHT_AM);
        RANDOM.clear();
        tokens.clear();
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, CLOCK);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, CLOCK, console);
        alpha = ready(salons.create("Booking Alpha", "#D6086A"));
        beta = ready(salons.create("Booking Beta", "#1F2937"));
    }

    private Salon ready(TestSalon salon) {
        String token = settings.signedIn(salon, StaffRole.OWNER).token();
        status(
                settings.call(
                        salon,
                        "PUT",
                        BookingRulesFixture.SETTINGS,
                        BookingRulesFixture.save(0, BookingRulesFixture.suggested()),
                        token),
                200);
        Http.Exchange hours = settings.call(salon, "GET", "/api/v1/salon/opening-hours/settings", null, token);
        status(hours, 200);
        status(
                settings.call(
                        salon,
                        "PUT",
                        "/api/v1/salon/opening-hours/settings",
                        Map.of("version", body(hours).get("version"), "weekly", openEveryDay("08:00", "20:00")),
                        token),
                200);
        UUID category = category(salon, token);
        UUID service = service(salon, token, category, "Cut and finish", 3_500, 45, 15);
        UUID nimali = member(salon, token, "Nimali Perera", List.of(service));
        UUID kasun = member(salon, token, "Kasun Silva", List.of(service));
        return new Salon(salon, token, category, service, nimali, kasun);
    }

    /** Every weekday open between the two times, as the opening hours save sends it. */
    static List<Map<String, Object>> openEveryDay(String opens, String closes) {
        List<Map<String, Object>> week = new ArrayList<>();
        for (java.time.DayOfWeek day : java.time.DayOfWeek.values()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("day", day.name().toLowerCase(java.util.Locale.ROOT));
            entry.put("open", true);
            entry.put("opens", opens);
            entry.put("closes", closes);
            week.add(entry);
        }
        return week;
    }

    UUID category(TestSalon salon, String token) {
        Http.Exchange list = settings.call(salon, "GET", "/api/v1/catalogue/categories", null, token);
        status(list, 200);
        return UUID.fromString((String) maps(body(list).get("items")).getFirst().get("id"));
    }

    /** A new service of the salon: its price in rupees, its minutes and its buffer's. */
    UUID service(TestSalon salon, String token, UUID category, String name, long rupees, int minutes, int buffer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("categoryId", category.toString());
        body.put("description", null);
        body.put("priceMinor", rupees * 100);
        body.put("durationMinutes", minutes);
        body.put("bufferMinutes", buffer);
        body.put("bookableOnline", true);
        Http.Exchange created = settings.call(salon, "POST", "/api/v1/catalogue/services", body, token);
        status(created, 201);
        return UUID.fromString((String) body(created).get("id"));
    }

    /** A new team member performing these services, with the salon's hours as their week. */
    UUID member(TestSalon salon, String token, String name, List<UUID> services) {
        Map<String, Object> person = new LinkedHashMap<>();
        person.put("name", name);
        person.put(
                "services",
                Map.of(
                        "categoryIds",
                        List.of(),
                        "serviceIds",
                        services.stream().map(UUID::toString).toList()));
        Http.Exchange added = settings.call(salon, "POST", "/api/v1/team/members", person, token);
        status(added, 201);
        return UUID.fromString((String) body(added).get("id"));
    }

    /** A signed-in member of staff in this role, signed in again when the clock has moved on. */
    String token(Salon salon, StaffRole role) {
        String key = salon.salon().id() + "/" + role + "/" + CLOCK.instant().getEpochSecond() / 120;
        return tokens.computeIfAbsent(
                key, ignored -> settings.signedIn(salon.salon(), role).token());
    }

    /** A Professional whose account is linked to this team member, signed in. */
    String linkedProfessional(Salon salon, UUID member) {
        SettingsTestSupport.Staff professional = settings.signedIn(salon.salon(), StaffRole.PROFESSIONAL);
        owner.update(
                "UPDATE staff_team_member SET sign_in_account_id = ? WHERE tenant_id = ? AND id = ?",
                professional.account().getId(),
                salon.salon().id(),
                member);
        return professional.token();
    }

    void moveClock(Duration by) {
        CLOCK.advance(by);
    }

    // --- requests ---------------------------------------------------------------------------------------------

    /** A request on the salon's host, its answer held to the committed contract. */
    Http.Exchange call(Salon salon, String method, String path, Object request, String token) {
        return send(salon, method, path, request, token, null);
    }

    Http.Exchange send(Salon salon, String method, String path, Object request, String token, String key) {
        Map<String, String> headers = new HashMap<>();
        if (request != null) {
            headers.put("Content-Type", "application/json");
        }
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        if (key != null) {
            headers.put("Idempotency-Key", key);
        }
        Http.Exchange exchange = http().sendOnHost(
                        method,
                        salon.salon().host(port()),
                        path,
                        headers,
                        request == null
                                ? null
                                : request instanceof String text ? text : json.writeValueAsString(request));
        contract.assertMatches(method, template(path), exchange);
        return exchange;
    }

    static String template(String path) {
        String bare = path.replaceFirst("\\?.*$", "");
        if (bare.equals(BOOKINGS + "/availability") || bare.equals(BOOKINGS + "/open-days")) {
            return bare;
        }
        return bare.replaceAll("^/api/v1/bookings/[^/]+", "/api/v1/bookings/{id}");
    }

    /** A hold placed with a new key; the answer as it came. */
    Http.Exchange place(Salon salon, Map<String, Object> hold, String token) {
        return send(salon, "POST", BOOKINGS, hold, token, UUID.randomUUID().toString());
    }

    /** A hold placed by the salon's Owner, answered 201, as a map. */
    Map<String, Object> held(Salon salon, Map<String, Object> hold) {
        Http.Exchange placed = place(salon, hold, ownerNow(salon));
        status(placed, 201);
        return body(placed);
    }

    /** A hold placed and confirmed by the salon's Owner. */
    Map<String, Object> confirmed(Salon salon, Map<String, Object> hold) {
        Map<String, Object> placed = held(salon, hold);
        Http.Exchange confirm = act(salon, placed, "confirm", ownerNow(salon));
        status(confirm, 200);
        return body(confirm);
    }

    /** The Owner's token, signed in again once the clock has moved far enough for the first to have run out. */
    String ownerNow(Salon salon) {
        return CLOCK.instant().isBefore(EIGHT_AM.plus(Duration.ofMinutes(4)))
                ? salon.owner()
                : token(salon, StaffRole.OWNER);
    }

    Http.Exchange act(Salon salon, Map<String, Object> booking, String action, String token) {
        return call(salon, "POST", BOOKINGS + "/" + booking.get("id") + "/" + action, null, token);
    }

    Http.Exchange read(Salon salon, Object id, String token) {
        return call(salon, "GET", BOOKINGS + "/" + id, null, token);
    }

    /** A hold for Nimali on {@code date} at {@code time}, for Dilani on the phone. */
    static Map<String, Object> hold(Salon salon, UUID member, String date, String time) {
        Map<String, Object> hold = new LinkedHashMap<>();
        hold.put("memberId", member.toString());
        hold.put("serviceId", salon.service().toString());
        hold.put("date", date);
        hold.put("time", time);
        hold.put("customer", customer("Dilani Fernando", "077 123 4567"));
        hold.put("channel", "phone");
        return hold;
    }

    static Map<String, Object> hold(Salon salon, String time) {
        return hold(salon, salon.nimali(), TOMORROW, time);
    }

    static Map<String, Object> customer(String name, String mobile) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("name", name);
        customer.put("mobile", mobile);
        return customer;
    }

    static Map<String, Object> with(Map<String, Object> hold, String field, Object value) {
        Map<String, Object> changed = new LinkedHashMap<>(hold);
        changed.put(field, value);
        return changed;
    }

    // --- assertions -------------------------------------------------------------------------------------------

    void status(Http.Exchange exchange, int expected) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(expected);
    }

    void refused(Http.Exchange exchange, int status, String code) {
        status(exchange, status);
        assertThat(body(exchange).get("code")).as(exchange.body()).isEqualTo(code);
    }

    /** A 400 naming exactly these fields. */
    void fieldErrors(Http.Exchange exchange, String... fields) {
        refused(exchange, 400, "bad_request");
        assertThat(maps(body(exchange).get("errors")).stream().map(error -> error.get("field")))
                .containsExactlyInAnyOrder((Object[]) fields);
    }

    Map<String, Object> body(Http.Exchange exchange) {
        return settings.body(exchange);
    }

    /** The salon's rows of a table, read as the owner. */
    long count(Salon salon, String table) {
        return owner.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class,
                salon.salon().id());
    }

    String stateInTheDatabase(Object id) {
        return owner.queryForObject("SELECT state FROM booking_appointment WHERE id = ?::uuid", String.class, id);
    }

    /** Work done for the salon outside a request, in a transaction of its own. */
    <T> T inSalon(Salon salon, Callable<T> work) {
        try {
            return TenantContext.callAs(
                    salons.asCurrent(salon.salon()),
                    () -> new TransactionTemplate(transactions).execute(status -> {
                        try {
                            return work.call();
                        } catch (RuntimeException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }));
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Requests sent all at once from their own threads; their answers in the order given. */
    List<Http.Exchange> atOnce(List<Callable<Http.Exchange>> requests) throws Exception {
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Http.Exchange>> answers = new ArrayList<>();
            for (Callable<Http.Exchange> request : requests) {
                answers.add(threads.submit(() -> {
                    start.await();
                    return request.call();
                }));
            }
            start.countDown();
            List<Http.Exchange> exchanges = new ArrayList<>();
            for (java.util.concurrent.Future<Http.Exchange> answer : answers) {
                exchanges.add(answer.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return exchanges;
        }
    }

    // JSON is read as untyped maps; each cast states the shape the assertion beside it checks.
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
