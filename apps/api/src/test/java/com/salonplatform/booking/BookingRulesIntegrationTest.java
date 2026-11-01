package com.salonplatform.booking;

import static com.salonplatform.booking.BookingRulesFixture.HISTORY;
import static com.salonplatform.booking.BookingRulesFixture.RULES;
import static com.salonplatform.booking.BookingRulesFixture.SETTINGS;
import static com.salonplatform.booking.BookingRulesFixture.map;
import static com.salonplatform.booking.BookingRulesFixture.save;
import static com.salonplatform.booking.BookingRulesFixture.suggested;
import static com.salonplatform.booking.BookingRulesFixture.with;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The booking rules on the running API (F04-S04): AC-1 (nothing saved before the first save, public null), AC-2
 * and AC-3 (every allowed value and every boundary, each broken rule a field error with nothing saved, whole
 * rupees, minor units with no rounding, limits scaled for another currency), AC-5 (a save is a new current
 * version, an identical save is {@code invalid_state}, a stale one {@code conflict}, of saves at once exactly one
 * is kept), AC-7 (the history, newest first, paginated, with its author) and AC-9 (the audit trail). Every answer
 * is held to the committed contract, the deposit and retention variants and the public null included.
 */
class BookingRulesIntegrationTest extends IntegrationTest {

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

    private final JdbcTemplate owner = TestDatabase.of(TestPostgres.shared()).asOwner();
    private BookingRulesFixture api;
    private SettingsTestSupport settings;
    private TestSalon alpha;
    private TestSalon beta;
    private SettingsTestSupport.Staff manager;

    @BeforeEach
    void twoSalons() {
        TestSalons salons = new TestSalons(owner);
        OperatorTestSupport console =
                new OperatorTestSupport(http(), port(), operators, operatorCrypto, json, environment, clock);
        settings = new SettingsTestSupport(
                http(), port(), salons, identityCrypto, json, transactions, entities, clock, console);
        api = new BookingRulesFixture(settings);
        alpha = salons.create("Rules Alpha", "#D6086A");
        beta = salons.create("Rules Beta", "#1F2937");
        manager = settings.signedIn(alpha, StaffRole.MANAGER);
    }

    private Map<String, Object> body(Http.Exchange exchange) {
        return api.body(exchange);
    }

    private long versions(TestSalon salon) {
        return owner.queryForObject(
                "SELECT count(*) FROM booking_rules_version WHERE tenant_id = ?", Long.class, salon.id());
    }

    private Http.Exchange put(long version, Map<String, Object> rules) {
        return api.call(alpha, "PUT", SETTINGS, save(version, rules), manager.token());
    }

    private void fieldError(Http.Exchange exchange, String field) {
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(400);
        assertThat(body(exchange)).containsEntry("code", "bad_request");
        List<?> errors = (List<?>) body(exchange).get("errors");
        assertThat(errors.stream()
                        .map(error -> ((Map<?, ?>) error).get("field"))
                        .toList())
                .isEqualTo(List.of(field));
    }

    // --- AC-1 ------------------------------------------------------------------------------------------------

    @Test
    void beforeTheFirstSaveTheRulesAreNullPubliclyAndVersionZeroInLkrForSettings() {
        Http.Exchange published = api.call(alpha, "GET", RULES, null, null);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.body()).isEqualTo("null");

        Map<String, Object> kept = body(api.call(alpha, "GET", SETTINGS, null, manager.token()));
        assertThat(kept)
                .containsEntry("version", 0)
                .containsEntry("rules", null)
                .containsEntry("currency", "LKR");
        assertThat(versions(alpha)).isZero();
    }

    // --- AC-5 ------------------------------------------------------------------------------------------------

    @Test
    void aSaveIsANewCurrentVersionAndThePreviousOneIsUnchanged() {
        Http.Exchange first = put(0, suggested());
        assertThat(first.status()).withFailMessage("%s", first.body()).isEqualTo(200);
        assertThat(body(first)).containsEntry("version", 1).containsEntry("currency", "LKR");
        assertThat(body(first).get("rules")).isEqualTo(json.readValue(json.writeValueAsString(suggested()), Map.class));

        Map<String, Object> publicFirst = body(api.call(alpha, "GET", RULES, null, null));
        String firstId = (String) publicFirst.get("versionId");
        assertThat(publicFirst).containsEntry("currency", "LKR");

        Map<String, Object> amount = map("kind", "amount", "amountMinor", 150_000L);
        Http.Exchange second = put(1, with("deposit", amount));
        assertThat(body(second)).containsEntry("version", 2);
        Map<String, Object> publicSecond = body(api.call(alpha, "GET", RULES, null, null));
        assertThat(publicSecond.get("versionId")).isNotEqualTo(firstId);
        assertThat(((Map<?, ?>) publicSecond.get("rules")).get("deposit"))
                .isEqualTo(Map.of("kind", "amount", "amountMinor", 150_000));

        assertThat(owner.queryForMap(
                        "SELECT version, deposit_kind, deposit_percent, deposit_cap_minor FROM booking_rules_version"
                                + " WHERE id = ?::uuid",
                        firstId))
                .containsEntry("version", 1L)
                .containsEntry("deposit_kind", "percentage")
                .containsEntry("deposit_percent", 20)
                .containsEntry("deposit_cap_minor", 200_000L);
        assertThat(versions(alpha)).isEqualTo(2);
        assertThat(versions(beta)).isZero();
        assertThat(api.call(beta, "GET", RULES, null, null).body()).isEqualTo("null");
    }

    @Test
    void savingTheCurrentRulesAgainCreatesNoVersionAndIsInvalidState() {
        put(0, suggested());
        Http.Exchange same = put(1, suggested());
        assertThat(same.status()).isEqualTo(409);
        assertThat(body(same)).containsEntry("code", "invalid_state");
        assertThat(versions(alpha)).isEqualTo(1);
    }

    @Test
    void aStaleVersionIsAConflictAndOfManySavesOfOneVersionExactlyOneIsKept() throws Exception {
        put(0, suggested());
        List<Callable<Integer>> saves = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int interval = List.of(10, 20, 30).get(i % 3);
            saves.add(() -> put(1, with("slotIntervalMinutes", interval)).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Future<Integer> result : executor.invokeAll(saves)) {
                statuses.add(result.get());
            }
        }
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(7);
        assertThat(versions(alpha)).isEqualTo(2);

        Http.Exchange stale = put(1, with("bookingHorizonDays", 90));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(body(stale)).containsEntry("code", "conflict");
        Http.Exchange ahead = put(5, with("bookingHorizonDays", 90));
        assertThat(body(ahead)).containsEntry("code", "conflict");
        assertThat(versions(alpha)).isEqualTo(2);
        assertThat(body(api.call(alpha, "GET", SETTINGS, null, manager.token())))
                .containsEntry("version", 2);
    }

    // --- AC-2 and AC-3: every allowed value ------------------------------------------------------------------

    static Stream<Arguments> allowedRules() {
        return Stream.of(
                Arguments.of("slotIntervalMinutes", 10),
                Arguments.of("slotIntervalMinutes", 20),
                Arguments.of("slotIntervalMinutes", 30),
                Arguments.of("minimumNoticeMinutes", 0),
                Arguments.of("minimumNoticeMinutes", 30),
                Arguments.of("minimumNoticeMinutes", 120),
                Arguments.of("minimumNoticeMinutes", 240),
                Arguments.of("minimumNoticeMinutes", 1440),
                Arguments.of("bookingHorizonDays", 14),
                Arguments.of("bookingHorizonDays", 30),
                Arguments.of("bookingHorizonDays", 90),
                Arguments.of("bookingHorizonDays", 180),
                Arguments.of("deposit", map("kind", "none")),
                Arguments.of("deposit", map("kind", "percentage", "percent", 1, "capMinor", 50_000L)),
                Arguments.of("deposit", map("kind", "percentage", "percent", 100, "capMinor", 9_007_199_254_740_900L)),
                Arguments.of("deposit", map("kind", "amount", "amountMinor", 10_000L)),
                Arguments.of("deposit", map("kind", "amount", "amountMinor", 10_000_000L)),
                Arguments.of("freeCancellationHours", 2),
                Arguments.of("freeCancellationHours", 6),
                Arguments.of("freeCancellationHours", 12),
                Arguments.of("freeCancellationHours", 48),
                Arguments.of("lateCancellation", map("kind", "nothing")),
                Arguments.of("lateCancellation", map("kind", "share", "percent", 1)),
                Arguments.of("lateCancellation", map("kind", "share", "percent", 100)),
                Arguments.of("noShow", map("kind", "share", "percent", 50)),
                Arguments.of("reschedule", map("limit", 0, "untilHours", null)),
                Arguments.of("reschedule", map("limit", 1, "untilHours", 2)),
                Arguments.of("reschedule", map("limit", null, "untilHours", 24)),
                Arguments.of("reschedule", map("limit", 2, "untilHours", 6)));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("allowedRules")
    void everyAllowedValueIsSavedAndServedExactly(String rule, Object value) {
        Http.Exchange saved = put(0, with(rule, value));
        assertThat(saved.status()).withFailMessage("%s", saved.body()).isEqualTo(200);
        Map<?, ?> served =
                (Map<?, ?>) body(api.call(alpha, "GET", RULES, null, null)).get("rules");
        assertThat(served.get(rule))
                .isEqualTo(json.readValue(json.writeValueAsString(Map.of("v", value)), Map.class)
                        .get("v"));
    }

    // --- AC-2 and AC-3: every boundary refused ----------------------------------------------------------------

    static Stream<Arguments> brokenRules() {
        return Stream.of(
                Arguments.of("slotIntervalMinutes", 5, "rules.slotIntervalMinutes"),
                Arguments.of("slotIntervalMinutes", 45, "rules.slotIntervalMinutes"),
                Arguments.of("minimumNoticeMinutes", 15, "rules.minimumNoticeMinutes"),
                Arguments.of("minimumNoticeMinutes", 2880, "rules.minimumNoticeMinutes"),
                Arguments.of("bookingHorizonDays", 7, "rules.bookingHorizonDays"),
                Arguments.of("bookingHorizonDays", 365, "rules.bookingHorizonDays"),
                Arguments.of("freeCancellationHours", 0, "rules.freeCancellationHours"),
                Arguments.of("freeCancellationHours", 72, "rules.freeCancellationHours"),
                Arguments.of(
                        "deposit",
                        map("kind", "percentage", "percent", 0, "capMinor", 200_000L),
                        "rules.deposit.percent"),
                Arguments.of(
                        "deposit",
                        map("kind", "percentage", "percent", 101, "capMinor", 200_000L),
                        "rules.deposit.percent"),
                Arguments.of(
                        "deposit",
                        map("kind", "percentage", "percent", 20, "capMinor", 49_900L),
                        "rules.deposit.capMinor"),
                Arguments.of(
                        "deposit",
                        map("kind", "percentage", "percent", 20, "capMinor", 49_999L),
                        "rules.deposit.capMinor"),
                Arguments.of(
                        "deposit",
                        map("kind", "percentage", "percent", 20, "capMinor", 50_050L),
                        "rules.deposit.capMinor"),
                Arguments.of(
                        "deposit",
                        map("kind", "percentage", "percent", 20, "capMinor", 9_007_199_254_740_992L),
                        "rules.deposit.capMinor"),
                Arguments.of("deposit", map("kind", "percentage", "percent", 20), "rules.deposit.capMinor"),
                Arguments.of("deposit", map("kind", "amount", "amountMinor", 9_900L), "rules.deposit.amountMinor"),
                Arguments.of("deposit", map("kind", "amount", "amountMinor", 10_000_100L), "rules.deposit.amountMinor"),
                Arguments.of("deposit", map("kind", "amount", "amountMinor", 10_050L), "rules.deposit.amountMinor"),
                Arguments.of("deposit", map("kind", "none", "percent", 20), "rules.deposit.percent"),
                Arguments.of(
                        "deposit",
                        map("kind", "amount", "amountMinor", 50_000L, "capMinor", 50_000L),
                        "rules.deposit.capMinor"),
                Arguments.of("deposit", map("kind", "half"), "rules.deposit.kind"),
                Arguments.of("lateCancellation", map("kind", "share", "percent", 0), "rules.lateCancellation.percent"),
                Arguments.of(
                        "lateCancellation", map("kind", "share", "percent", 101), "rules.lateCancellation.percent"),
                Arguments.of(
                        "lateCancellation", map("kind", "deposit", "percent", 50), "rules.lateCancellation.percent"),
                Arguments.of("noShow", map("kind", "nothing"), "rules.noShow.kind"),
                Arguments.of("noShow", map("kind", "share", "percent", 101), "rules.noShow.percent"),
                Arguments.of("reschedule", map("limit", 3, "untilHours", 12), "rules.reschedule.limit"),
                Arguments.of("reschedule", map("limit", 1, "untilHours", 48), "rules.reschedule.untilHours"),
                Arguments.of("reschedule", map("limit", 0, "untilHours", 12), "rules.reschedule.untilHours"),
                Arguments.of("reschedule", map("limit", 2, "untilHours", null), "rules.reschedule.untilHours"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @MethodSource("brokenRules")
    void eachBrokenRuleIsAFieldErrorOnThatFieldAndNothingIsSaved(String rule, Object value, String field) {
        fieldError(put(0, with(rule, value)), field);
        assertThat(versions(alpha)).isZero();
    }

    @Test
    void aValueOfTheWrongJsonTypeOrAnUnknownFieldIsRefusedNamingIt() {
        Map<String, Object> text = with("slotIntervalMinutes", "15");
        Http.Exchange coerced = put(0, text);
        assertThat(coerced.status()).isEqualTo(400);
        assertThat(coerced.body()).contains("slotIntervalMinutes");
        Map<String, Object> extra = suggested();
        extra.put("colour", "pink");
        fieldError(put(0, extra), "rules.colour");
        assertThat(versions(alpha)).isZero();
    }

    @Test
    void anotherCurrencysLimitsAreTheSameWholeUnitsScaledByItsFractionDigits() {
        owner.update("UPDATE tenant SET currency = 'JPY' WHERE id = ?", alpha.id());
        owner.update("UPDATE tenant SET currency = 'KWD' WHERE id = ?", beta.id());
        assertThat(body(api.call(alpha, "GET", SETTINGS, null, manager.token())))
                .containsEntry("currency", "JPY");

        // The yen has no minor unit: Rs. 100 is 100, and any whole amount is whole yen.
        fieldError(put(0, with("deposit", map("kind", "amount", "amountMinor", 99L))), "rules.deposit.amountMinor");
        Http.Exchange yen = put(0, with("deposit", map("kind", "amount", "amountMinor", 101L)));
        assertThat(yen.status()).withFailMessage("%s", yen.body()).isEqualTo(200);
        assertThat(body(yen)).containsEntry("currency", "JPY");

        // The dinar has three: at least 500 dinars of cap is 500000, in whole dinars.
        String betasManager = settings.signedIn(beta, StaffRole.MANAGER).token();
        Http.Exchange short1 = api.call(
                beta,
                "PUT",
                SETTINGS,
                save(0, with("deposit", map("kind", "percentage", "percent", 20, "capMinor", 499_000L))),
                betasManager);
        fieldError(short1, "rules.deposit.capMinor");
        Http.Exchange fraction = api.call(
                beta,
                "PUT",
                SETTINGS,
                save(0, with("deposit", map("kind", "percentage", "percent", 20, "capMinor", 500_500L))),
                betasManager);
        fieldError(fraction, "rules.deposit.capMinor");
        Http.Exchange dinar = api.call(
                beta,
                "PUT",
                SETTINGS,
                save(0, with("deposit", map("kind", "percentage", "percent", 20, "capMinor", 500_000L))),
                betasManager);
        assertThat(dinar.status()).withFailMessage("%s", dinar.body()).isEqualTo(200);
        assertThat(owner.queryForObject(
                        "SELECT currency FROM booking_rules_version WHERE tenant_id = ?", String.class, beta.id()))
                .isEqualTo("KWD");
    }

    // --- AC-7 ------------------------------------------------------------------------------------------------

    @Test
    void theHistoryListsEachVersionNewestFirstWithItsAuthorAndChangedRulesPaginated() {
        put(0, suggested());
        put(1, with("slotIntervalMinutes", 30));
        Map<String, Object> third = with("slotIntervalMinutes", 30);
        third.put("noShow", map("kind", "share", "percent", 100));
        third.put("reschedule", map("limit", 0, "untilHours", null));
        put(2, third);

        Map<String, Object> page = body(api.call(alpha, "GET", HISTORY + "?page=0&size=2", null, manager.token()));
        assertThat(page).containsEntry("page", 0).containsEntry("size", 2).containsEntry("total", 3);
        List<?> items = (List<?>) page.get("items");
        assertThat(items.stream().map(item -> ((Map<?, ?>) item).get("version")).toList())
                .isEqualTo(List.of(3, 2));
        Map<?, ?> newest = (Map<?, ?>) items.getFirst();
        assertThat(newest.get("changedFields")).isEqualTo(List.of("noShow", "reschedule"));
        assertThat(newest.get("createdBy")).isEqualTo(manager.account().getId().toString());
        assertThat(newest.get("createdByLabel")).isEqualTo(manager.account().getEmail());
        assertThat(newest.get("createdAt")).isNotNull();

        List<?> last = (List<?>) body(api.call(alpha, "GET", HISTORY + "?page=1&size=2", null, manager.token()))
                .get("items");
        assertThat(last).hasSize(1);
        assertThat(((Map<?, ?>) last.getFirst()).get("changedFields"))
                .isEqualTo(List.of(
                        "slotIntervalMinutes",
                        "minimumNoticeMinutes",
                        "bookingHorizonDays",
                        "deposit",
                        "freeCancellationHours",
                        "lateCancellation",
                        "noShow",
                        "reschedule"));
        fieldError(api.call(alpha, "GET", HISTORY + "?page=-1", null, manager.token()), "page");
        fieldError(api.call(alpha, "GET", HISTORY + "?size=101", null, manager.token()), "page");
        assertThat(body(api.call(
                        beta,
                        "GET",
                        HISTORY,
                        null,
                        settings.signedIn(beta, StaffRole.OWNER).token())))
                .containsEntry("total", 0);
    }

    // --- AC-9 ------------------------------------------------------------------------------------------------

    @Test
    void everySaveIsInTheAuditTrailWithActorTimeVersionAndChangedRuleNamesOnly() {
        put(0, suggested());
        put(1, with("deposit", map("kind", "amount", "amountMinor", 123_400L)));
        put(1, with("deposit", map("kind", "none")));

        List<Map<String, Object>> entries = owner.queryForList(
                "SELECT actor_kind, actor_id, subject_kind, subject_id, created_at, after_values::text AS after"
                        + " FROM audit_entry WHERE tenant_id = ? AND action = 'booking.rules_saved' ORDER BY created_at",
                alpha.id());
        assertThat(entries).hasSize(2);
        List<String> ids = owner.queryForList(
                "SELECT id::text FROM booking_rules_version WHERE tenant_id = ? ORDER BY version",
                String.class,
                alpha.id());
        for (int i = 0; i < 2; i++) {
            assertThat(entries.get(i))
                    .containsEntry("actor_kind", "staff")
                    .containsEntry("actor_id", manager.account().getId().toString())
                    .containsEntry("subject_kind", "booking_rules_version")
                    .containsEntry("subject_id", ids.get(i));
            assertThat(entries.get(i).get("created_at")).isNotNull();
        }
        assertThat((String) entries.get(1).get("after"))
                .contains("\"version\": 2")
                .contains("[\"deposit\"]");
        assertThat((String) entries.get(1).get("after"))
                .doesNotContain("123400")
                .doesNotContain("amount");
    }
}
