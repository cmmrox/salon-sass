package com.salonplatform.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccountService;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * What F06-S01's integration tests share: two salons, alpha's Owner, calls held to the committed contract, a plan of a
 * given number of seats, and the database read as its owner.
 */
abstract class TwoSalonTeam extends IntegrationTest {

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

    @Autowired
    Clock clock;

    @PersistenceContext
    EntityManager entities;

    final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    TestSalons salons;
    SettingsTestSupport settings;
    TeamFixture api;
    TestSalon alpha;
    TestSalon beta;
    String salonOwner;

    @BeforeEach
    void twoSalonsAndAlphasOwner() {
        salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new TeamFixture(settings);
        alpha = salons.create("Team Alpha", "#D6086A");
        beta = salons.create("Team Beta", "#1F2937");
        salonOwner = settings.signedIn(alpha, StaffRole.OWNER).token();
    }

    /** Puts the salon on a plan of this many seats. */
    void onPlanOf(TestSalon salon, int seats) {
        UUID plan = UUID.randomUUID();
        String key = "team" + plan.toString().substring(0, 8);
        owner.update("""
                INSERT INTO plan (id, key, name, staff_seats, branches, message_allowance, custom_domain, dedicated_app,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 1, 0, false, false, now(), now())
                """, plan, key, key, seats);
        owner.update("UPDATE tenant SET plan_id = ? WHERE id = ?", plan, salon.id());
    }

    void status(Http.Exchange exchange, int expected) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(expected);
    }

    void refused(Http.Exchange exchange, int status, String code) {
        status(exchange, status);
        assertThat(api.body(exchange).get("code")).isEqualTo(code);
    }

    /** A 409 {@code entitlement_exceeded} naming the staff seats. */
    void noFreeSeat(Http.Exchange exchange) {
        refused(exchange, 409, "entitlement_exceeded");
        assertThat(maps(api.body(exchange).get("errors")))
                .extracting(error -> error.get("field"))
                .containsExactly("staffSeats");
    }

    /** A 400 naming exactly these fields. */
    void fieldErrors(Http.Exchange exchange, String... fields) {
        refused(exchange, 400, "bad_request");
        assertThat(maps(api.body(exchange).get("errors")).stream().map(error -> error.get("field")))
                .containsExactlyInAnyOrder((Object[]) fields);
    }

    /** Adds a person with no sign-in, as alpha's Owner, and returns them. */
    Map<String, Object> added(String name) {
        Http.Exchange exchange = api.call(alpha, "POST", TeamFixture.MEMBERS, TeamFixture.person(name), salonOwner);
        status(exchange, 201);
        return api.body(exchange);
    }

    /** Creates a sign-in account in alpha, as its Owner, and returns its id. */
    String account(String role) {
        Http.Exchange exchange = api.call(
                alpha,
                "POST",
                TeamFixture.IDENTITY_STAFF,
                TeamFixture.account(TeamFixture.email("seat"), role),
                salonOwner);
        status(exchange, 201);
        return (String) api.body(exchange).get("id");
    }

    /** The id of one of the salon's default categories. */
    UUID category(TestSalon salon, String token, String name) {
        Http.Exchange list = settings.call(salon, "GET", "/api/v1/catalogue/categories", null, token);
        status(list, 200);
        return maps(api.body(list).get("items")).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> UUID.fromString((String) category.get("id")))
                .findFirst()
                .orElseThrow();
    }

    /** A new service of the salon, bookable online or not, as the catalogue returns it. */
    Map<String, Object> service(TestSalon salon, String token, String name, UUID categoryId, boolean online) {
        Map<String, Object> service = new java.util.LinkedHashMap<>();
        service.put("name", name);
        service.put("categoryId", categoryId);
        service.put("priceMinor", 150000);
        service.put("durationMinutes", 30);
        service.put("bufferMinutes", 0);
        service.put("bookableOnline", online);
        Http.Exchange created = settings.call(salon, "POST", "/api/v1/catalogue/services", service, token);
        status(created, 201);
        return api.body(created);
    }

    /** Moves a service to another category, as the catalogue's own save does. */
    void moveService(TestSalon salon, String token, Map<String, Object> service, UUID categoryId) {
        Map<String, Object> save = new java.util.LinkedHashMap<>();
        save.put("name", service.get("name"));
        save.put("categoryId", categoryId);
        save.put("priceMinor", 150000);
        save.put("durationMinutes", 30);
        save.put("bufferMinutes", 0);
        save.put("bookableOnline", service.get("bookableOnline"));
        save.put("version", service.get("version"));
        status(settings.call(salon, "PUT", "/api/v1/catalogue/services/" + service.get("id"), save, token), 200);
    }

    void deleteService(TestSalon salon, String token, Map<String, Object> service) {
        status(
                settings.call(
                        salon,
                        "DELETE",
                        "/api/v1/catalogue/services/" + service.get("id") + "?version=" + service.get("version"),
                        null,
                        token),
                204);
    }

    /** The member as the API reads them now. */
    Map<String, Object> read(Object memberId) {
        Http.Exchange exchange = api.call(alpha, "GET", TeamFixture.MEMBERS + "/" + memberId, null, salonOwner);
        status(exchange, 200);
        return api.body(exchange);
    }

    /** What the salon's own staff read as seats used. */
    long usageSeats(TestSalon salon, String token) {
        Http.Exchange exchange = settings.call(salon, "GET", TeamFixture.ENTITLEMENTS, null, token);
        status(exchange, 200);
        return ((Number) map(api.body(exchange).get("usage")).get("staffSeats")).longValue();
    }

    /** Seats used, read from the rows: active accounts plus active members with no linked account. */
    long seatsInTheDatabase(TestSalon salon) {
        return owner.queryForObject("""
                SELECT (SELECT count(*) FROM identity_staff WHERE tenant_id = ? AND active)
                     + (SELECT count(*) FROM staff_team_member
                         WHERE tenant_id = ? AND removed_at IS NULL AND sign_in_account_id IS NULL)
                """, Long.class, salon.id(), salon.id());
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
