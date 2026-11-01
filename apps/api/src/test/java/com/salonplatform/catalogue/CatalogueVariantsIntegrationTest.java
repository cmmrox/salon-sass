package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.MENU;
import static com.salonplatform.catalogue.CatalogueFixture.SERVICES;
import static com.salonplatform.catalogue.CatalogueFixture.save;
import static com.salonplatform.catalogue.CatalogueFixture.saveWithVariants;
import static com.salonplatform.catalogue.CatalogueFixture.service;
import static com.salonplatform.catalogue.CatalogueFixture.variant;
import static com.salonplatform.catalogue.CatalogueFixture.withVariants;
import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.Http;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A service's variants (F05-S02 AC-1 to AC-4, AC-9): every limit a field error on that variant's field with nothing
 * saved; variants part of the terms, each change a new version with ids kept; segments per variant with the
 * service's buffer; the lowest and highest values; the menu and {@link ServiceTerms}; a duplicate's new ids.
 */
class CatalogueVariantsIntegrationTest extends TwoSalonCatalogue {

    @Autowired
    ServiceTerms serviceTerms;

    private Map<String, Object> cut() {
        return api.created(
                alpha,
                manager,
                withVariants(
                        service(colour, "Haircut", 0, 0, 10),
                        "Hair length",
                        variant(null, "Short", 2500, 30),
                        variant(null, "Medium", 3200, 45),
                        variant(null, "Long", 4000, 60)));
    }

    private Http.Exchange create(Map<String, Object> body) {
        return api.call(alpha, "POST", SERVICES, body, manager);
    }

    @Test
    void aServiceWithVariantsHasEachWithItsOwnPriceAndTimeAndTheLowestAndHighest() {
        Map<String, Object> created = cut();

        Map<String, Object> terms = terms(created);
        assertThat(terms)
                .containsEntry("variantSetName", "Hair length")
                .containsEntry("priceMinor", 250_000)
                .containsEntry("durationMinutes", 30)
                .containsEntry("bufferMinutes", 10)
                .containsEntry("priceMinMinor", 250_000)
                .containsEntry("priceMaxMinor", 400_000)
                .containsEntry("durationMinMinutes", 30)
                .containsEntry("durationMaxMinutes", 60);
        assertThat(variants(created)).extracting(v -> v.get("name")).containsExactly("Short", "Medium", "Long");
        assertThat(variants(created).get(2))
                .containsEntry("priceMinor", 400_000)
                .containsEntry("durationMinutes", 60)
                .containsEntry(
                        "segments",
                        List.of(Map.of("kind", "active", "minutes", 60), Map.of("kind", "buffer", "minutes", 10)));
        // The version holds the lowest price and the shortest time itself (decision 095).
        assertThat(owner.queryForMap(
                        "SELECT price_minor, segment_minutes::text AS minutes, variant_set_name FROM"
                                + " catalogue_terms_version WHERE id = ?::uuid",
                        terms.get("versionId")))
                .containsEntry("price_minor", 250_000L)
                .containsEntry("minutes", "{30,10}")
                .containsEntry("variant_set_name", "Hair length");
        assertThat(owner.queryForList(
                        "SELECT name FROM catalogue_terms_variant WHERE terms_version_id = ?::uuid ORDER BY position",
                        String.class,
                        terms.get("versionId")))
                .containsExactly("Short", "Medium", "Long");
    }

    @Test
    void twoAndTenVariantsAreKeptAndOneOrElevenAreRefusedWithNothingSaved() {
        List<Map<String, Object>> ten = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> variant(null, "Size " + i, 1000 + i, 30))
                .toList();
        Map<String, Object> body = service(colour, "Ten sizes", 0, 0, 0);
        body.remove("priceMinor");
        body.remove("durationMinutes");
        body.put("variants", Map.of("name", "Size", "items", ten));
        assertThat(variants(api.created(alpha, manager, body))).hasSize(10);
        assertThat(variants(api.created(
                        alpha,
                        manager,
                        withVariants(
                                service(colour, "Two sizes", 0, 0, 0),
                                "Size",
                                variant(null, "S", 1000, 30),
                                variant(null, "L", 2000, 45)))))
                .hasSize(2);
        List<Map<String, Object>> before = snapshot();

        List<Map<String, Object>> eleven = new ArrayList<>(ten);
        eleven.add(variant(null, "Size 11", 1500, 30));
        body.put("name", "Eleven sizes");
        body.put("variants", Map.of("name", "Size", "items", eleven));
        fieldErrors(create(body), "variants.items");
        body.put("variants", Map.of("name", "Size", "items", List.of(variant(null, "Only", 1500, 30))));
        fieldErrors(create(body), "variants.items");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void everyBrokenVariantRuleIsAFieldErrorOnThatVariantsField() {
        List<Map<String, Object>> before = snapshot();
        Map<String, Object> base = service(colour, "Rules", 0, 0, 0);
        base.remove("priceMinor");
        base.remove("durationMinutes");

        Map<String, Object> body = new LinkedHashMap<>(base);
        body.put(
                "variants",
                Map.of(
                        "name",
                        "H",
                        "items",
                        List.of(
                                variant(null, "x".repeat(30), 100, 5),
                                variant(null, "y".repeat(31), 99, 7),
                                variant(null, "SHORT", 500_000, 480),
                                variant(null, "short", 500_001, 485))));
        fieldErrors(
                create(body),
                "variants.name",
                "variants.items[1].name",
                "variants.items[1].priceMinor",
                "variants.items[1].durationMinutes",
                "variants.items[3].name",
                "variants.items[3].priceMinor",
                "variants.items[3].durationMinutes");

        body.put(
                "variants",
                Map.of(
                        "name",
                        "x".repeat(31),
                        "items",
                        List.of(variant(null, "A", 1000, 30), variant(null, "B", 1000, 30))));
        fieldErrors(create(body), "variants.name");

        // With variants the service has no price or time of its own.
        Map<String, Object> both = withVariants(
                service(colour, "Both", 0, 0, 0), "Size", variant(null, "A", 1000, 30), variant(null, "B", 1000, 30));
        both.put("priceMinor", 100_000);
        both.put("durationMinutes", 30);
        fieldErrors(create(both), "priceMinor", "durationMinutes");

        // An id on a new service names no variant of it.
        fieldErrors(
                create(withVariants(
                        service(colour, "Ids", 0, 0, 0),
                        "Size",
                        variant(UUID.randomUUID(), "A", 1000, 30),
                        variant(null, "B", 1000, 30))),
                "variants.items[0].id");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void everyChangeOfTheVariantsMakesANewVersionKeepingEachVariantsIdAndNoChangeMakesNone() throws Exception {
        Map<String, Object> first = cut();
        List<Map<String, Object>> sent = CatalogueFixture.variantsOf(first);
        UUID shortId = UUID.fromString((String) sent.get(0).get("id"));
        UUID longId = UUID.fromString((String) sent.get(2).get("id"));

        // Nothing changed.
        refused(put(first, save(first, null, null)), 409, "invalid_state");

        // Renamed and repriced: same ids, a new version.
        Map<String, Object> second = api.body(put(
                first,
                saveWithVariants(
                        first,
                        "Hair length",
                        variant(shortId, "Short hair", 2500, 30),
                        sent.get(1),
                        variant(longId, "Long", 4500, 75))));
        assertThat(terms(second)).containsEntry("number", 2).containsEntry("priceMaxMinor", 450_000);
        assertThat(variants(second))
                .extracting(v -> v.get("id"))
                .containsExactly(shortId.toString(), sent.get(1).get("id"), longId.toString());

        // Reordered, one removed, one added: ids kept for those that stay, a new one for the new.
        Map<String, Object> third = api.body(put(
                second,
                saveWithVariants(
                        second,
                        "Hair length",
                        variant(longId, "Long", 4500, 75),
                        variant(shortId, "Short hair", 2500, 30),
                        variant(null, "Extra long", 5500, 90))));
        assertThat(terms(third)).containsEntry("number", 3);
        assertThat(variants(third)).extracting(v -> v.get("id")).startsWith(longId.toString(), shortId.toString());
        assertThat(variants(third).get(2).get("id"))
                .isNotIn(shortId.toString(), longId.toString(), sent.get(1).get("id"));

        // A renamed set alone is a new version too.
        Map<String, Object> fourth = api.body(put(
                third,
                CatalogueFixture.withVariantList(
                        save(third, null, null), "Length", CatalogueFixture.variantsOf(third))));
        assertThat(terms(fourth)).containsEntry("number", 4).containsEntry("variantSetName", "Length");

        // The first version, with its variants, is unchanged and readable.
        UUID firstVersion = UUID.fromString((String) terms(first).get("versionId"));
        ServiceTermsVersion original =
                as(alpha, () -> serviceTerms.version(firstVersion)).orElseThrow();
        assertThat(original.variantSetName()).isEqualTo("Hair length");
        assertThat(original.variants())
                .extracting(ServiceVariant::name, ServiceVariant::priceMinor)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("Short", 250_000L),
                        org.assertj.core.api.Assertions.tuple("Medium", 320_000L),
                        org.assertj.core.api.Assertions.tuple("Long", 400_000L));
        assertThat(original.variants().get(0).variantId()).isEqualTo(shortId);
        assertThat(original.variants().get(2).segments())
                .containsExactly(
                        new ServiceSegment(SegmentKind.ACTIVE, 60), new ServiceSegment(SegmentKind.BUFFER, 10));
        assertThat(original.priceMinMinor()).isEqualTo(250_000);
        assertThat(original.priceMaxMinor()).isEqualTo(400_000);
        assertThat(original.durationMinMinutes()).isEqualTo(30);
        assertThat(original.durationMaxMinutes()).isEqualTo(60);
        assertThat(as(alpha, () -> serviceTerms.current(UUID.fromString((String) first.get("id"))))
                        .orElseThrow()
                        .number())
                .isEqualTo(4);
    }

    @Test
    void aVariantIdTheCurrentVersionDoesNotHaveIsRefused() {
        Map<String, Object> first = cut();
        Map<String, Object> second = api.body(put(
                first,
                saveWithVariants(
                        first,
                        "Hair length",
                        CatalogueFixture.variantsOf(first).get(0),
                        CatalogueFixture.variantsOf(first).get(1))));
        Object removed = variants(first).get(2).get("id");
        List<Map<String, Object>> before = snapshot();

        fieldErrors(
                put(
                        second,
                        saveWithVariants(
                                second,
                                "Hair length",
                                CatalogueFixture.variantsOf(second).get(0),
                                variant(removed, "Long", 4000, 60))),
                "variants.items[1].id");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void turningVariantsOffNeedsAPriceAndTimeAndReturnsTheServiceToOne() {
        Map<String, Object> first = cut();
        Map<String, Object> off = new LinkedHashMap<>(save(first, null, null));
        off.remove("variants");

        fieldErrors(put(first, off), "priceMinor", "durationMinutes");

        off.put("priceMinor", 300_000);
        off.put("durationMinutes", 40);
        Map<String, Object> single = api.body(put(first, off));
        assertThat(terms(single))
                .containsEntry("number", 2)
                .containsEntry("variants", List.of())
                .containsEntry("variantSetName", null)
                .containsEntry("priceMinMinor", 300_000)
                .containsEntry("priceMaxMinor", 300_000)
                .containsEntry("durationMinutes", 40);
    }

    @Test
    void theMenuGivesEachVariantWithTheCustomersTimeAndTheRange() {
        Map<String, Object> created = cut();
        api.created(alpha, manager, service(nails, "Gel polish", 2500, 45, 0));

        Map<String, Object> menu = api.body(api.call(alpha, "GET", MENU, null, null));
        Map<String, Object> item = CatalogueFixture.maps(
                        CatalogueFixture.maps(menu.get("categories")).getFirst().get("services"))
                .getFirst();

        assertThat(item)
                .containsEntry("id", created.get("id"))
                .containsEntry("variantSetName", "Hair length")
                .containsEntry("priceMinor", 250_000)
                .containsEntry("durationMinutes", 30)
                .containsEntry("priceMinMinor", 250_000)
                .containsEntry("priceMaxMinor", 400_000)
                .containsEntry("durationMinMinutes", 30)
                .containsEntry("durationMaxMinutes", 60);
        // The customer's time leaves the 10-minute buffer out.
        assertThat(CatalogueFixture.maps(item.get("variants")))
                .containsExactly(
                        Map.of(
                                "id",
                                variants(created).get(0).get("id"),
                                "name",
                                "Short",
                                "priceMinor",
                                250_000,
                                "durationMinutes",
                                30),
                        Map.of(
                                "id",
                                variants(created).get(1).get("id"),
                                "name",
                                "Medium",
                                "priceMinor",
                                320_000,
                                "durationMinutes",
                                45),
                        Map.of(
                                "id",
                                variants(created).get(2).get("id"),
                                "name",
                                "Long",
                                "priceMinor",
                                400_000,
                                "durationMinutes",
                                60));
        Map<String, Object> polish = CatalogueFixture.maps(
                        CatalogueFixture.maps(menu.get("categories")).get(1).get("services"))
                .getFirst();
        assertThat(polish).containsEntry("variants", List.of()).containsEntry("variantSetName", null);
    }

    @Test
    void aDuplicateCopiesTheVariantsWithNewIdsAndTheDepositButNotPopular() {
        Map<String, Object> body = withVariants(
                service(colour, "Haircut", 0, 0, 10),
                "Hair length",
                variant(null, "Short", 2500, 30),
                variant(null, "Long", 4000, 60));
        body.put("deposit", Map.of("kind", "percentage", "percent", 25));
        Map<String, Object> source = api.created(alpha, manager, body);
        Map<String, Object> chosen = api.body(api.call(
                alpha,
                "PUT",
                SERVICES + "/" + source.get("id") + "/popular",
                Map.of("popular", true, "version", source.get("version")),
                manager));
        assertThat(chosen).containsEntry("popular", true);

        Map<String, Object> copy =
                api.body(api.call(alpha, "POST", SERVICES + "/" + source.get("id") + "/duplicate", null, manager));

        assertThat(copy)
                .containsEntry("popular", false)
                .containsEntry("deposit", Map.of("kind", "percentage", "percent", 25));
        assertThat(variants(copy))
                .extracting(v -> v.get("name"), v -> v.get("priceMinor"), v -> v.get("durationMinutes"))
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("Short", 250_000, 30),
                        org.assertj.core.api.Assertions.tuple("Long", 400_000, 60));
        assertThat(variants(copy))
                .extracting(v -> v.get("id"))
                .doesNotContainAnyElementsOf(
                        variants(source).stream().map(v -> v.get("id")).toList());
        assertThat(terms(copy)).containsEntry("variantSetName", "Hair length").containsEntry("number", 1);
    }

    @Test
    void aVariantChangeIsAuditedWithItsVariantsBeforeAndAfter() {
        Map<String, Object> first = cut();
        api.body(put(
                first,
                saveWithVariants(
                        first,
                        "Hair length",
                        CatalogueFixture.variantsOf(first).get(0),
                        CatalogueFixture.variantsOf(first).get(1))));

        Map<String, Object> entry = owner.queryForMap(
                "SELECT before_values::text AS before, after_values::text AS after FROM audit_entry WHERE tenant_id = ?"
                        + " AND action = 'catalogue.service_updated'",
                alpha.id());
        assertThat((String) entry.get("before")).contains("\"Long\"").contains("\"variantSetName\": \"Hair length\"");
        assertThat((String) entry.get("after")).doesNotContain("\"Long\"").contains("\"termsVersion\": 2");
    }
}
