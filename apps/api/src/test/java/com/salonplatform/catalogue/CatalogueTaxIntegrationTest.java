package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.TAX;
import static com.salonplatform.catalogue.CatalogueFixture.TAX_HISTORY;
import static com.salonplatform.catalogue.CatalogueFixture.TAX_SETTINGS;
import static com.salonplatform.catalogue.CatalogueFixture.tax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.testing.Http;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/**
 * The salon's tax settings (F05-S03 AC-1 to AC-3, AC-6, AC-11, AC-12): null and version 0 before the first save; every
 * broken rule a 400 on its field with nothing saved; each save an immutable version carrying the configured name and
 * rate; identical, stale and simultaneous saves; the history; the public read without the registration number;
 * {@link TaxSettings} for the caller's salon only; roles, support, suspended and read-only salons; another salon;
 * the audit trail. Every answer is held to the committed contract.
 */
class CatalogueTaxIntegrationTest extends TwoSalonCatalogue {

    @Autowired
    TaxSettings taxSettings;

    private Http.Exchange save(Map<String, Object> body) {
        return api.call(alpha, "PUT", TAX_SETTINGS, body, manager);
    }

    private Map<String, Object> saved(Map<String, Object> body) {
        Http.Exchange exchange = save(body);
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(200);
        return api.body(exchange);
    }

    /** Every setting, in the tab's order: what a branch's first version changes. */
    private static final List<String> ALL_FIELDS = List.of(
            "vat.registered",
            "vat.registrationNumber",
            "vat.ratePercent",
            "vat.name",
            "serviceCharge.on",
            "serviceCharge.percent",
            "serviceCharge.onProducts",
            "serviceCharge.name");

    private long versions() {
        return count("SELECT count(*) FROM catalogue_tax_version WHERE tenant_id = ?");
    }

    private UUID currentVersionId() {
        return UUID.fromString(
                (String) api.body(api.call(alpha, "GET", TAX, null, null)).get("versionId"));
    }

    private List<Map<String, Object>> rows() {
        return owner.queryForList(
                "SELECT row_to_json(v)::text AS row FROM catalogue_tax_version v WHERE tenant_id = ? ORDER BY version",
                alpha.id());
    }

    @Test
    void beforeTheFirstSaveThePublicTaxIsNullAndTheSettingsAreVersionZeroWithTheConfiguredNamesAndRates() {
        Http.Exchange published = api.call(alpha, "GET", TAX, null, null);
        assertThat(published.status()).isEqualTo(200);
        assertThat(published.body().strip()).isEqualTo("null");

        Map<String, Object> view = api.body(api.call(alpha, "GET", TAX_SETTINGS, null, manager));
        assertThat(view.get("version")).isEqualTo(0);
        assertThat(view.get("settings")).isNull();
        assertThat(view.get("configured"))
                .isEqualTo(Map.of(
                        "vatName", "VAT",
                        "vatRatePercent", 18,
                        "registrationLabel", "VAT registration number",
                        "serviceChargeName", "Service charge",
                        "serviceChargeMaxPercent", 20));
        assertThat(versions()).isZero();
    }

    @Test
    void aSaveMakesTheCurrentVersionWithTheConfiguredNameAndRateAndThePublicReadNeverCarriesTheNumber() {
        Map<String, Object> view = saved(tax(0, "LK-134-567", 10, false));

        assertThat(view.get("version")).isEqualTo(1);
        assertThat(view.get("settings"))
                .isEqualTo(Map.of(
                        "vat", mapOf("registered", true, "registrationNumber", "LK-134-567"),
                        "serviceCharge", mapOf("on", true, "percent", 10, "onProducts", false)));
        Http.Exchange published = api.call(alpha, "GET", TAX, null, null);
        Map<String, Object> current = api.body(published);
        assertThat(current.get("vat")).isEqualTo(Map.of("name", "VAT", "ratePercent", 18));
        assertThat(current.get("serviceCharge"))
                .isEqualTo(Map.of("name", "Service charge", "percent", 10, "onProducts", false));
        assertThat(published.body()).doesNotContain("LK-134-567").doesNotContainIgnoringCase("registration");
        assertThat(owner.queryForMap(
                        "SELECT vat_name, vat_rate_percent, service_charge_name FROM catalogue_tax_version"
                                + " WHERE tenant_id = ?",
                        alpha.id()))
                .isEqualTo(Map.of("vat_name", "VAT", "vat_rate_percent", 18, "service_charge_name", "Service charge"));

        saved(tax(1, null, null, false));
        Map<String, Object> off = api.body(api.call(alpha, "GET", TAX, null, null));
        assertThat(off.get("vat")).isNull();
        assertThat(off.get("serviceCharge")).isNull();
        assertThat(off.get("versionId")).isNotEqualTo(current.get("versionId"));
    }

    @Test
    void everyBrokenRuleIsAFieldErrorAndNothingIsSaved() {
        saved(tax(0, null, 5, false));
        List<Map<String, Object>> before = rows();

        fieldErrors(save(tax(1, "A".repeat(21), null, false)), "vat.registrationNumber");
        fieldErrors(save(tax(1, "AB 12", null, false)), "vat.registrationNumber");
        fieldErrors(save(tax(1, "", null, false)), "vat.registrationNumber");
        fieldErrors(save(tax(1, null, 0, false)), "serviceCharge.percent");
        fieldErrors(save(tax(1, null, 21, false)), "serviceCharge.percent");
        Map<String, Object> asText = tax(1, null, 10, false);
        mapAt(asText, "serviceCharge").put("percent", "10");
        fieldErrors(save(asText), "serviceCharge.percent");
        Map<String, Object> part = tax(1, null, 10, false);
        mapAt(part, "serviceCharge").put("percent", 10.5);
        fieldErrors(save(part), "serviceCharge.percent");
        Map<String, Object> numberAsNumber = tax(1, "X", null, false);
        mapAt(numberAsNumber, "vat").put("registrationNumber", 12345);
        fieldErrors(save(numberAsNumber), "vat.registrationNumber");
        Map<String, Object> numberWhileOff = tax(1, null, null, false);
        mapAt(numberWhileOff, "vat").put("registrationNumber", "LK-1");
        fieldErrors(save(numberWhileOff), "vat.registrationNumber");
        fieldErrors(save(tax(1, null, null, true)), "serviceCharge.onProducts");
        Map<String, Object> unknown = tax(1, null, 10, false);
        mapAt(unknown, "vat").put("rate", 5);
        fieldErrors(save(unknown), "vat.rate");
        Map<String, Object> noVersion = tax(1, null, 10, false);
        noVersion.remove("version");
        fieldErrors(save(noVersion), "version");

        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void anIdenticalSaveIsInvalidStateAStaleOrFutureOneIsConflictAndEarlierVersionsNeverChange() {
        saved(tax(0, "LK-1001", 10, false));
        String first = rows().getFirst().get("row").toString();
        saved(tax(1, "LK-1001", 12, true));
        List<Map<String, Object>> before = rows();

        refused(save(tax(2, "LK-1001", 12, true)), 409, "invalid_state");
        refused(save(tax(1, "LK-2002", null, false)), 409, "conflict");
        refused(save(tax(0, "LK-2002", null, false)), 409, "conflict");
        refused(save(tax(9, "LK-2002", null, false)), 409, "conflict");

        assertThat(rows()).isEqualTo(before);
        assertThat(rows().getFirst().get("row")).isEqualTo(first);
        assertThatExceptionOfType(DataAccessException.class)
                .as("no role changes a version")
                .isThrownBy(() -> owner.update(
                        "UPDATE catalogue_tax_version SET vat_rate_percent = 15 WHERE tenant_id = ?", alpha.id()));
    }

    @Test
    void ofEightSavesOfOneVersionSentAtOnceExactlyOneIsKept() throws Exception {
        saved(tax(0, null, 5, false));
        List<Callable<Integer>> saves = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            int percent = 10 + n;
            saves.add(() -> save(tax(1, null, percent, false)).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Integer> answer : pool.invokeAll(saves)) {
                statuses.add(answer.get());
            }
        }
        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(versions()).isEqualTo(2);
    }

    @Test
    void theHistoryListsEachVersionNewestFirstWithItsAuthorAndTheSettingsItChangedPaged() {
        String owners = settings.signedIn(alpha, StaffRole.OWNER).token();
        saved(tax(0, "LK-1", null, false));
        saved(tax(1, "LK-1", 10, false));
        assertThat(api.call(alpha, "PUT", TAX_SETTINGS, tax(2, "LK-1", 10, true), owners)
                        .status())
                .isEqualTo(200);

        Map<String, Object> history = api.body(api.call(alpha, "GET", TAX_HISTORY, null, manager));
        assertThat(history.get("total")).isEqualTo(3);
        List<Map<String, Object>> items = CatalogueFixture.maps(history.get("items"));
        assertThat(items).extracting(item -> item.get("version")).containsExactly(3, 2, 1);
        assertThat(items.get(0).get("changedFields")).isEqualTo(List.of("serviceCharge.onProducts"));
        assertThat(items.get(1).get("changedFields")).isEqualTo(List.of("serviceCharge.on", "serviceCharge.percent"));
        assertThat(items.get(2).get("changedFields")).isEqualTo(ALL_FIELDS);
        assertThat(items.get(0).get("createdBy")).isNotEqualTo(items.get(1).get("createdBy"));
        assertThat(items)
                .allSatisfy(item -> assertThat(item.get("createdByLabel")).isNotNull());

        Map<String, Object> second = api.body(api.call(alpha, "GET", TAX_HISTORY + "?page=1&size=2", null, manager));
        assertThat(CatalogueFixture.maps(second.get("items")))
                .extracting(item -> item.get("version"))
                .containsExactly(1);
        for (String query : List.of("?size=0", "?size=101", "?page=-1")) {
            refused(api.call(alpha, "GET", TAX_HISTORY + query, null, manager), 400, "bad_request");
        }
    }

    @Test
    void taxSettingsGivesTheCallersVersionsByIdUnchangedAfterLaterSavesAndBreaksPricesDown() throws Exception {
        saved(tax(0, "LK-77", 10, false));
        UUID first = currentVersionId();
        saved(tax(1, "LK-77", 12, false));
        saved(tax(2, null, 12, true));
        UUID onProducts = currentVersionId();
        saved(tax(3, null, null, false));

        TaxSettingsVersion kept = as(alpha, () -> taxSettings.version(first)).orElseThrow();
        assertThat(kept.version()).isEqualTo(1);
        assertThat(kept.vat()).isEqualTo(new TaxSettingsVersion.Vat("VAT", 18, true, "LK-77"));
        assertThat(kept.serviceCharge())
                .isEqualTo(new TaxSettingsVersion.ServiceCharge("Service charge", true, 10, false));
        TaxSettingsVersion current = as(alpha, () -> taxSettings.current()).orElseThrow();
        assertThat(current.version()).isEqualTo(4);
        assertThat(as(beta, () -> taxSettings.version(first)))
                .as("another salon's id")
                .isEmpty();
        assertThat(as(beta, () -> taxSettings.current())).isEmpty();
        assertThat(as(alpha, () -> taxSettings.version(UUID.randomUUID()))).isEmpty();

        // Rs. 2,500 with 10% and 18%: service charge Rs. 192.60 and VAT Rs. 381.36, the prototype's figures.
        assertThat(taxSettings.servicePrice(kept, 250_000)).isEqualTo(new PriceParts(250_000, 192_604, 19_260, 38_136));
        // A product carries VAT only while the service charge is not on products, and none without its VAT choice.
        assertThat(taxSettings.productPrice(kept, 320_000, true))
                .isEqualTo(new PriceParts(320_000, 271_186, 0, 48_814));
        assertThat(taxSettings.productPrice(kept, 320_000, false)).isEqualTo(new PriceParts(320_000, 320_000, 0, 0));
        TaxSettingsVersion third =
                as(alpha, () -> taxSettings.version(onProducts)).orElseThrow();
        assertThat(taxSettings.productPrice(third, 112_000, true))
                .isEqualTo(new PriceParts(112_000, 100_000, 12_000, 0));
        assertThat(taxSettings.servicePrice(current, 250_000)).isEqualTo(new PriceParts(250_000, 250_000, 0, 0));
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void otherRolesAreRefusedTheSettingsButReadThePublicTax(StaffRole role) {
        String token = settings.signedIn(alpha, role).token();
        refused(api.call(alpha, "GET", TAX_SETTINGS, null, token), 403, "permission_denied");
        refused(api.call(alpha, "GET", TAX_HISTORY, null, token), 403, "permission_denied");
        refused(api.call(alpha, "PUT", TAX_SETTINGS, tax(0, null, 10, false), token), 403, "permission_denied");
        assertThat(api.call(alpha, "GET", TAX, null, token).status()).isEqualTo(200);
        assertThat(versions()).isZero();
    }

    @Test
    void aSupportSessionReadsTheSettingsAndHistoryAndIsRefusedTheSave() {
        saved(tax(0, "LK-5", null, false));
        String support = settings.supportSession(alpha);
        assertThat(api.call(alpha, "GET", TAX_SETTINGS, null, support).status()).isEqualTo(200);
        assertThat(api.call(alpha, "GET", TAX_HISTORY, null, support).status()).isEqualTo(200);
        refused(api.call(alpha, "PUT", TAX_SETTINGS, tax(1, null, 10, false), support), 403, "support_read_only");
        refused(api.call(alpha, "GET", TAX_SETTINGS, null, null), 401, "invalid_session");
        assertThat(versions()).isEqualTo(1);
    }

    @Test
    void aSuspendedSalonIsRefusedEverywhereAndAReadOnlyOneReadsAndIsRefusedTheSave() {
        saved(tax(0, null, 10, false));
        salons.suspend(alpha);
        try {
            refused(api.call(alpha, "GET", TAX, null, null), 403, "salon_suspended");
            refused(api.call(alpha, "GET", TAX_SETTINGS, null, manager), 403, "salon_suspended");
            refused(api.call(alpha, "PUT", TAX_SETTINGS, tax(1, null, 12, false), manager), 403, "salon_suspended");
        } finally {
            salons.activate(alpha);
        }
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        assertThat(api.call(alpha, "GET", TAX_SETTINGS, null, manager).status()).isEqualTo(200);
        assertThat(api.call(alpha, "GET", TAX, null, null).status()).isEqualTo(200);
        refused(api.call(alpha, "PUT", TAX_SETTINGS, tax(1, null, 12, false), manager), 403, "salon_read_only");
        assertThat(versions()).isEqualTo(1);
    }

    @Test
    void anotherSalonsStaffNeverReadOrSaveThisSalonsSettingsAndEachSalonHasItsOwn() {
        saved(tax(0, "A-1", null, false));
        String betasOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        List<Map<String, Object>> before = rows();

        refused(api.call(alpha, "GET", TAX_SETTINGS, null, betasOwner), 404, "not_found");
        refused(api.call(alpha, "GET", TAX_HISTORY, null, betasOwner), 404, "not_found");
        refused(api.call(alpha, "PUT", TAX_SETTINGS, tax(1, null, 10, false), betasOwner), 404, "not_found");
        assertThat(rows()).isEqualTo(before);

        assertThat(api.call(beta, "GET", TAX, null, null).body().strip()).isEqualTo("null");
        assertThat(api.body(api.call(beta, "PUT", TAX_SETTINGS, tax(0, null, 15, false), betasOwner))
                        .get("version"))
                .isEqualTo(1);
        assertThat(api.body(api.call(alpha, "GET", TAX, null, null)).get("serviceCharge"))
                .isNull();
    }

    @Test
    void everySaveIsAuditedWithTheActorAndTheValuesBeforeAndAfterAndARefusalIsNot() {
        saved(tax(0, null, 10, false));
        saved(tax(1, null, 12, false));
        refused(save(tax(1, "LK-1", null, false)), 409, "conflict");

        List<Map<String, Object>> entries = owner.queryForList(
                "SELECT actor_kind, before_values->>'serviceChargePercent' AS before,"
                        + " after_values->>'serviceChargePercent' AS after, after_values->>'version' AS version"
                        + " FROM audit_entry WHERE tenant_id = ? AND action = 'catalogue.tax_saved'"
                        + " ORDER BY created_at, after_values->>'version'",
                alpha.id());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0)).containsEntry("before", null).containsEntry("after", "10");
        assertThat(entries.get(1))
                .containsEntry("before", "10")
                .containsEntry("after", "12")
                .containsEntry("version", "2");
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @SuppressWarnings("unchecked") // The bodies built by CatalogueFixture.tax.
    private static Map<String, Object> mapAt(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }
}
