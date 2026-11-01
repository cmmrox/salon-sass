package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.PRODUCTS;
import static com.salonplatform.catalogue.CatalogueFixture.product;
import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * The salon's products (F05-S03 AC-8, AC-9, AC-11, AC-12): added, listed by name, saved and deleted in minor units of
 * the salon's currency; every broken rule a 400 on its field with nothing saved; 300 at most, also when added at once;
 * stale versions; {@link Products} for the caller's salon only; roles, support, suspended and read-only salons;
 * another salon; the audit trail. Every answer is held to the committed contract.
 */
class CatalogueProductsIntegrationTest extends TwoSalonCatalogue {

    @Autowired
    Products products;

    private Http.Exchange add(Map<String, Object> body) {
        return api.call(alpha, "POST", PRODUCTS, body, manager);
    }

    private Map<String, Object> added(Map<String, Object> body) {
        Http.Exchange exchange = add(body);
        assertThat(exchange.status()).withFailMessage("%s", exchange.body()).isEqualTo(201);
        return api.body(exchange);
    }

    private Http.Exchange save(Map<String, Object> product, Map<String, Object> body) {
        return api.call(alpha, "PUT", PRODUCTS + "/" + product.get("id"), body, manager);
    }

    private static Map<String, Object> edit(Map<String, Object> product, String field, Object value) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (String key : List.of("name", "priceMinor", "chargesVat", "soldAtCheckout", "version")) {
            body.put(key, product.get(key));
        }
        body.put(field, value);
        return body;
    }

    private List<Map<String, Object>> listed(String token) {
        return CatalogueFixture.maps(
                api.body(api.call(alpha, "GET", PRODUCTS, null, token)).get("items"));
    }

    private List<Map<String, Object>> rows() {
        return owner.queryForList(
                "SELECT row_to_json(p)::text AS row FROM catalogue_product p WHERE tenant_id = ? ORDER BY id",
                alpha.id());
    }

    @Test
    void anOwnerOrManagerAddsListsSavesAndDeletesProductsInMinorUnits() {
        Map<String, Object> shampoo = added(product("Argan oil shampoo 250 ml", 3200, false, true));
        assertThat(shampoo)
                .containsEntry("name", "Argan oil shampoo 250 ml")
                .containsEntry("priceMinor", 320_000)
                .containsEntry("currency", "LKR")
                .containsEntry("chargesVat", false)
                .containsEntry("soldAtCheckout", true);
        Map<String, Object> mask = added(product("  keratin hair mask  ", 4500, true, false));
        assertThat(mask.get("name")).isEqualTo("keratin hair mask");
        assertThat(listed(manager))
                .extracting(p -> p.get("name"))
                .containsExactly("Argan oil shampoo 250 ml", "keratin hair mask");

        Map<String, Object> saved =
                api.body(save(shampoo, edit(edit(shampoo, "priceMinor", 350_000), "chargesVat", true)));
        assertThat(saved).containsEntry("priceMinor", 350_000).containsEntry("chargesVat", true);
        assertThat(saved.get("version")).isNotEqualTo(shampoo.get("version"));
        List<Map<String, Object>> before = rows();
        refused(save(shampoo, edit(shampoo, "priceMinor", 360_000)), 409, "conflict");
        refused(
                api.call(
                        alpha,
                        "DELETE",
                        PRODUCTS + "/" + shampoo.get("id") + "?version=" + shampoo.get("version"),
                        null,
                        manager),
                409,
                "conflict");
        refused(save(saved, edit(saved, "name", saved.get("name"))), 409, "invalid_state");
        fieldErrors(api.call(alpha, "DELETE", PRODUCTS + "/" + saved.get("id"), null, manager), "version");
        assertThat(rows()).isEqualTo(before);

        assertThat(api.call(
                                alpha,
                                "DELETE",
                                PRODUCTS + "/" + saved.get("id") + "?version=" + saved.get("version"),
                                null,
                                manager)
                        .status())
                .isEqualTo(204);
        assertThat(listed(manager)).extracting(p -> p.get("id")).containsExactly(mask.get("id"));
        refused(
                api.call(
                        alpha,
                        "DELETE",
                        PRODUCTS + "/" + saved.get("id") + "?version=" + saved.get("version"),
                        null,
                        manager),
                404,
                "not_found");
        assertThat(added(product("ARGAN OIL SHAMPOO 250 ML", 100, true, true)).get("name"))
                .as("a deleted product's name is free")
                .isEqualTo("ARGAN OIL SHAMPOO 250 ML");
    }

    @Test
    void everyBrokenRuleIsAFieldErrorOnAnAddAndASaveAndNothingIsSaved() {
        Map<String, Object> oil = added(product("Hair oil", 1500, true, true));
        List<Map<String, Object>> before = rows();
        Map<String, Object> noName = product("x", 1, true, true);
        noName.remove("name");
        fieldErrors(add(noName), "name");
        fieldErrors(add(product("A", 100, true, true)), "name");
        fieldErrors(add(product("P" + "p".repeat(40), 100, true, true)), "name");
        fieldErrors(add(product("HAIR OIL", 100, true, true)), "name");
        fieldErrors(add(product("Comb", 0, true, true)), "priceMinor");
        fieldErrors(add(product("Comb", 500_001, true, true)), "priceMinor");
        Map<String, Object> partRupee = product("Comb", 1, true, true);
        partRupee.put("priceMinor", 150);
        fieldErrors(add(partRupee), "priceMinor");
        Map<String, Object> asText = product("Comb", 1, true, true);
        asText.put("priceMinor", "100");
        fieldErrors(add(asText), "priceMinor");
        Map<String, Object> vatText = product("Comb", 1, true, true);
        vatText.put("chargesVat", "yes");
        fieldErrors(add(vatText), "chargesVat");
        Map<String, Object> soldNumber = product("Comb", 1, true, true);
        soldNumber.put("soldAtCheckout", 1);
        fieldErrors(add(soldNumber), "soldAtCheckout");
        Map<String, Object> noSettings = product("Comb", 1, true, true);
        noSettings.remove("chargesVat");
        noSettings.remove("soldAtCheckout");
        fieldErrors(add(noSettings), "chargesVat", "soldAtCheckout");
        Map<String, Object> unknown = product("Comb", 1, true, true);
        unknown.put("stock", 5);
        fieldErrors(add(unknown), "stock");

        fieldErrors(save(oil, edit(oil, "name", "A")), "name");
        fieldErrors(save(oil, edit(oil, "priceMinor", 50_000_100)), "priceMinor");
        fieldErrors(save(oil, edit(oil, "chargesVat", null)), "chargesVat");
        Map<String, Object> noVersion = edit(oil, "priceMinor", 200_000);
        noVersion.remove("version");
        fieldErrors(save(oil, noVersion), "version");
        assertThat(rows()).isEqualTo(before);

        assertThat(added(product("Ab", 1, true, true)).get("priceMinor")).isEqualTo(100);
        assertThat(added(product("P" + "p".repeat(39), 500_000, true, true)).get("priceMinor"))
                .isEqualTo(50_000_000);
    }

    @Test
    void aSalonHoldsThreeHundredProductsAlsoWhenAddedAtOnceAndA301stIsLimitReached() throws Exception {
        owner.update(
                "INSERT INTO catalogue_product (tenant_id, id, branch_id, name, price_minor, currency, charges_vat,"
                        + " sold_at_checkout, version, created_at, updated_at) SELECT ?, gen_random_uuid(), ?,"
                        + " 'Seeded ' || n, 10000, 'LKR', true, true, 0, now(), now() FROM generate_series(1, 295) n",
                alpha.id(),
                salons.defaultBranch(alpha));
        List<Callable<Integer>> adds = new ArrayList<>();
        for (int n = 0; n < 8; n++) {
            String name = "At once " + n;
            adds.add(() -> add(product(name, 100, true, true)).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Integer> answer : pool.invokeAll(adds)) {
                statuses.add(answer.get());
            }
        }
        assertThat(statuses.stream().filter(status -> status == 201)).hasSize(5);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(3);
        assertThat(count("SELECT count(*) FROM catalogue_product WHERE tenant_id = ?"))
                .isEqualTo(300);
        refused(add(product("One too many", 100, true, true)), 409, "limit_reached");
        assertThat(listed(manager)).hasSize(300);
    }

    @Test
    void productsGivesTheCallersProductByIdAndThoseSoldAtCheckoutButNeverADeletedOneOrAnotherSalons() throws Exception {
        Map<String, Object> oil = added(product("Hair oil", 1500, false, true));
        Map<String, Object> comb = added(product("comb", 300, true, true));
        Map<String, Object> mask = added(product("Mask", 4500, true, false));
        UUID oilId = UUID.fromString((String) oil.get("id"));

        assertThat(as(alpha, () -> products.product(oilId)))
                .contains(new Product(oilId, "Hair oil", 150_000, "LKR", false, true));
        assertThat(as(alpha, () -> products.product(UUID.fromString((String) mask.get("id")))))
                .isPresent();
        assertThat(as(alpha, () -> products.soldAtCheckout()))
                .extracting(Product::name)
                .containsExactly("comb", "Hair oil");
        assertThat(as(beta, () -> products.product(oilId)))
                .as("another salon's id")
                .isEmpty();
        assertThat(as(beta, () -> products.soldAtCheckout())).isEmpty();

        api.call(alpha, "DELETE", PRODUCTS + "/" + comb.get("id") + "?version=" + comb.get("version"), null, manager);
        assertThat(as(alpha, () -> products.product(UUID.fromString((String) comb.get("id")))))
                .isEmpty();
        assertThat(as(alpha, () -> products.soldAtCheckout()))
                .extracting(Product::name)
                .containsExactly("Hair oil");
    }

    @ParameterizedTest
    @EnumSource(
            value = StaffRole.class,
            names = {"RECEPTIONIST", "PROFESSIONAL", "ACCOUNTANT"})
    void otherRolesReadProductsAndAreRefusedEveryChange(StaffRole role) {
        Map<String, Object> oil = added(product("Hair oil", 1500, true, true));
        String token = settings.signedIn(alpha, role).token();
        List<Map<String, Object>> before = rows();

        assertThat(listed(token)).hasSize(1);
        everyChangeRefused(oil, token, 403, "permission_denied");
        assertThat(rows()).isEqualTo(before);
    }

    private void everyChangeRefused(Map<String, Object> product, String token, int status, String code) {
        refused(api.call(alpha, "POST", PRODUCTS, product("Comb", 300, true, true), token), status, code);
        refused(
                api.call(alpha, "PUT", PRODUCTS + "/" + product.get("id"), edit(product, "priceMinor", 200_000), token),
                status,
                code);
        refused(
                api.call(
                        alpha,
                        "DELETE",
                        PRODUCTS + "/" + product.get("id") + "?version=" + product.get("version"),
                        null,
                        token),
                status,
                code);
    }

    @Test
    void aSupportSessionReadsAndIsRefusedEveryChangeAndNoSessionIsRefusedEverything() {
        Map<String, Object> oil = added(product("Hair oil", 1500, true, true));
        String support = settings.supportSession(alpha);
        List<Map<String, Object>> before = rows();

        assertThat(listed(support)).hasSize(1);
        everyChangeRefused(oil, support, 403, "support_read_only");
        refused(api.call(alpha, "GET", PRODUCTS, null, null), 401, "invalid_session");
        everyChangeRefused(oil, null, 401, "invalid_session");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void aSuspendedSalonIsRefusedEverywhereAndAReadOnlyOneReadsAndIsRefusedEveryChange() {
        Map<String, Object> oil = added(product("Hair oil", 1500, true, true));
        List<Map<String, Object>> before = rows();
        salons.suspend(alpha);
        try {
            refused(api.call(alpha, "GET", PRODUCTS, null, manager), 403, "salon_suspended");
            everyChangeRefused(oil, manager, 403, "salon_suspended");
        } finally {
            salons.activate(alpha);
        }
        owner.update("UPDATE tenant SET access_state = 'read_only' WHERE id = ?", alpha.id());
        assertThat(listed(manager)).hasSize(1);
        everyChangeRefused(oil, manager, 403, "salon_read_only");
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void anotherSalonsProductsAnswerAsUnknownIdsAndNeverAppear() {
        Map<String, Object> oil = added(product("Hair oil", 1500, true, true));
        String betasOwner = settings.signedIn(beta, StaffRole.OWNER).token();
        List<Map<String, Object>> before = rows();

        refused(api.call(alpha, "GET", PRODUCTS, null, betasOwner), 404, "not_found");
        everyChangeRefused(oil, betasOwner, 404, "not_found");
        // On its own hostname beta finds none of alpha's products, exactly as an id that names none.
        String path = PRODUCTS + "/" + oil.get("id");
        String unknown = PRODUCTS + "/" + UUID.randomUUID();
        Http.Exchange theirs = api.call(beta, "PUT", path, edit(oil, "priceMinor", 200_000), betasOwner);
        Http.Exchange none = api.call(beta, "PUT", unknown, edit(oil, "priceMinor", 200_000), betasOwner);
        refused(theirs, 404, "not_found");
        refused(none, 404, "not_found");
        refused(api.call(beta, "DELETE", path + "?version=0", null, betasOwner), 404, "not_found");
        assertThat(CatalogueFixture.maps(api.body(api.call(beta, "GET", PRODUCTS, null, betasOwner))
                        .get("items")))
                .isEmpty();
        assertThat(api.call(beta, "POST", PRODUCTS, product("Hair oil", 100, true, true), betasOwner)
                        .status())
                .as("a name is unique within a salon only")
                .isEqualTo(201);
        assertThat(rows()).isEqualTo(before);
    }

    @Test
    void everyChangeIsAuditedWithTheValuesBeforeAndAfterAndARefusalIsNot() {
        Map<String, Object> oil = added(product("Hair oil", 1500, true, true));
        Map<String, Object> saved = api.body(save(oil, edit(oil, "priceMinor", 175_000)));
        refused(save(oil, edit(oil, "priceMinor", 190_000)), 409, "conflict");
        api.call(alpha, "DELETE", PRODUCTS + "/" + oil.get("id") + "?version=" + saved.get("version"), null, manager);

        List<Map<String, Object>> entries = owner.queryForList(
                "SELECT action, actor_kind, before_values->>'priceMinor' AS before, after_values->>'priceMinor' AS after"
                        + " FROM audit_entry WHERE tenant_id = ? AND subject_id = ? ORDER BY created_at",
                alpha.id(),
                oil.get("id"));
        assertThat(entries)
                .extracting(entry -> entry.get("action"))
                .containsExactly("catalogue.product_added", "catalogue.product_updated", "catalogue.product_deleted");
        assertThat(entries)
                .allSatisfy(entry -> assertThat(entry.get("actor_kind")).isEqualTo("staff"));
        assertThat(entries.get(0)).containsEntry("before", null).containsEntry("after", "150000");
        assertThat(entries.get(1)).containsEntry("before", "150000").containsEntry("after", "175000");
        assertThat(entries.get(2)).containsEntry("before", "175000").containsEntry("after", null);
    }
}
