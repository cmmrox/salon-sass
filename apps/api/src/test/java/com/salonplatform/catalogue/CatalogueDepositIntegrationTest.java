package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.MENU;
import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.save;
import static com.salonplatform.catalogue.CatalogueFixture.service;
import static com.salonplatform.catalogue.CatalogueFixture.variant;
import static com.salonplatform.catalogue.CatalogueFixture.withVariants;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A service's own deposit (F05-S02 AC-7, AC-8, BKG-09): the salon's rule by default, none, a percentage or an amount
 * up to the service's lowest price; every broken rule a field error; part of the terms, so a change is a new version
 * and the earlier one keeps its deposit; given by the service, the menu and {@link ServiceTerms}.
 */
class CatalogueDepositIntegrationTest extends TwoSalonCatalogue {

    @Autowired
    ServiceTerms serviceTerms;

    private static Map<String, Object> deposited(Map<String, Object> body, Map<String, Object> deposit) {
        Map<String, Object> changed = new LinkedHashMap<>(body);
        changed.put("deposit", deposit);
        return changed;
    }

    private Http.Exchange create(Map<String, Object> body) {
        return api.call(alpha, "POST", SERVICES, body, manager);
    }

    @Test
    void aServiceKeepsTheSalonsRuleUnlessItSetsItsOwn() {
        Map<String, Object> plain = api.created(alpha, manager, service(colour, "Plain", 2500, 45, 0));
        Map<String, Object> none =
                api.created(alpha, manager, deposited(service(colour, "Free", 2500, 45, 0), Map.of("kind", "none")));
        Map<String, Object> rule = api.created(
                alpha, manager, deposited(service(colour, "Rule", 2500, 45, 0), Map.of("kind", "salon_rule")));
        Map<String, Object> share = api.created(
                alpha,
                manager,
                deposited(service(colour, "Share", 2500, 45, 0), Map.of("kind", "percentage", "percent", 100)));
        Map<String, Object> amount = api.created(
                alpha,
                manager,
                deposited(service(colour, "Amount", 2500, 45, 0), Map.of("kind", "amount", "amountMinor", 250_000)));

        assertThat(plain).containsEntry("deposit", Map.of("kind", "salon_rule"));
        assertThat(rule).containsEntry("deposit", Map.of("kind", "salon_rule"));
        assertThat(none).containsEntry("deposit", Map.of("kind", "none"));
        assertThat(share).containsEntry("deposit", Map.of("kind", "percentage", "percent", 100));
        assertThat(amount).containsEntry("deposit", Map.of("kind", "amount", "amountMinor", 250_000));
        Map<String, Object> menu = api.body(api.call(alpha, "GET", MENU, null, null));
        assertThat(CatalogueFixture.maps(
                        CatalogueFixture.maps(menu.get("categories")).getFirst().get("services")))
                .extracting(item -> item.get("name"), item -> item.get("deposit"))
                .contains(
                        org.assertj.core.api.Assertions.tuple(
                                "Amount", Map.of("kind", "amount", "amountMinor", 250_000)),
                        org.assertj.core.api.Assertions.tuple("Free", Map.of("kind", "none")),
                        org.assertj.core.api.Assertions.tuple("Plain", Map.of("kind", "salon_rule")));
    }

    @Test
    void everyBrokenDepositRuleIsAFieldErrorAndNothingIsSaved() {
        List<Map<String, Object>> before = snapshot();
        Map<String, Object> body = service(colour, "Deposit", 2500, 45, 0);

        fieldErrors(create(deposited(body, Map.of("kind", "percentage", "percent", 0))), "deposit.percent");
        fieldErrors(create(deposited(body, Map.of("kind", "percentage", "percent", 101))), "deposit.percent");
        fieldErrors(create(deposited(body, Map.of("kind", "percentage"))), "deposit.percent");
        fieldErrors(create(deposited(body, Map.of("kind", "amount", "amountMinor", 9_900))), "deposit.amountMinor");
        fieldErrors(
                create(deposited(
                        service(colour, "Dear", 200_000, 45, 0), Map.of("kind", "amount", "amountMinor", 10_000_100))),
                "deposit.amountMinor");
        fieldErrors(create(deposited(body, Map.of("kind", "amount", "amountMinor", 10_050))), "deposit.amountMinor");
        // Not more than the service's lowest price: Rs. 2,500 here.
        fieldErrors(create(deposited(body, Map.of("kind", "amount", "amountMinor", 260_000))), "deposit.amountMinor");
        fieldErrors(
                create(deposited(body, Map.of("kind", "amount", "amountMinor", 10_000, "percent", 10))),
                "deposit.percent");
        fieldErrors(create(deposited(body, Map.of("kind", "none", "percent", 10))), "deposit.percent");
        fieldErrors(create(deposited(body, Map.of("kind", "card"))), "deposit.kind");
        fieldErrors(create(deposited(body, Map.of("percent", 10))), "deposit.kind");
        fieldErrors(create(deposited(body, Map.of("kind", "none", "capMinor", 10))), "deposit.capMinor");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void anAmountIsCheckedAgainstTheLowestVariantPrice() {
        Map<String, Object> body = withVariants(
                service(colour, "Cut", 0, 0, 0),
                "Hair length",
                variant(null, "Short", 1500, 30),
                variant(null, "Long", 4000, 60));

        fieldErrors(create(deposited(body, Map.of("kind", "amount", "amountMinor", 150_100))), "deposit.amountMinor");
        assertThat(api.created(alpha, manager, deposited(body, Map.of("kind", "amount", "amountMinor", 150_000))))
                .containsEntry("deposit", Map.of("kind", "amount", "amountMinor", 150_000));
    }

    @Test
    void aDepositChangeIsANewTermsVersionAndTheEarlierKeepsItsDeposit() throws Exception {
        Map<String, Object> first = api.created(alpha, manager, service(colour, "Facial", 6000, 60, 0));
        UUID firstVersion = UUID.fromString((String) terms(first).get("versionId"));

        Map<String, Object> second = api.body(put(first, save(first, "deposit", Map.of("kind", "none"))));
        assertThat(terms(second)).containsEntry("number", 2);
        Map<String, Object> third =
                api.body(put(second, save(second, "deposit", Map.of("kind", "percentage", "percent", 20))));
        assertThat(terms(third)).containsEntry("number", 3);
        refused(put(third, save(third, null, null)), 409, "invalid_state");

        assertThat(as(alpha, () -> serviceTerms.version(firstVersion))
                        .orElseThrow()
                        .deposit())
                .isEqualTo(new ServiceDeposit.SalonRule());
        assertThat(as(
                                alpha,
                                () -> serviceTerms.version(
                                        UUID.fromString((String) terms(second).get("versionId"))))
                        .orElseThrow()
                        .deposit())
                .isEqualTo(new ServiceDeposit.None());
        assertThat(as(alpha, () -> serviceTerms.current(UUID.fromString((String) first.get("id"))))
                        .orElseThrow()
                        .deposit())
                .isEqualTo(new ServiceDeposit.Percentage(20));
        assertThat(owner.queryForObject(
                        "SELECT after_values->'deposit'->>'kind' FROM audit_entry WHERE tenant_id = ? AND action ="
                                + " 'catalogue.service_updated' ORDER BY created_at DESC LIMIT 1",
                        String.class,
                        alpha.id()))
                .isEqualTo("percentage");
    }
}
