package com.salonplatform.catalogue;

import static com.salonplatform.catalogue.CatalogueFixture.TAX;
import static com.salonplatform.catalogue.CatalogueFixture.TAX_HISTORY;
import static com.salonplatform.catalogue.CatalogueFixture.TAX_SETTINGS;
import static com.salonplatform.catalogue.CatalogueFixture.tax;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * A change of configuration rewrites no version (F05-S03 AC-3, "Design notes"): with VAT configured at 15%, a version
 * saved earlier under 18% still says 18%, the settings tell the tab the rate now, and a save with the same choices is
 * a new version at 15% whose history names only the rate. The salon's earlier version is written here as an
 * application running under the earlier configuration wrote it.
 */
@TestPropertySource(properties = "salon.catalogue.tax.vat.rate-percent=15")
class CatalogueTaxConfigurationIntegrationTest extends TwoSalonCatalogue {

    @Autowired
    TaxSettings taxSettings;

    @Test
    void aVersionSavedUnderTheEarlierRateKeepsItAndTheNextSaveCopiesTheNewOne() throws Exception {
        UUID earlier = UUID.randomUUID();
        owner.update(
                "INSERT INTO catalogue_tax_version (tenant_id, id, branch_id, version, vat_name, vat_rate_percent,"
                        + " vat_registered, vat_registration_number, service_charge_name, service_charge_on,"
                        + " service_charge_percent, service_charge_on_products, changed_fields, created_by, created_at,"
                        + " updated_at) VALUES (?, ?, ?, 1, 'VAT', 18, true, 'LK-9', 'Service charge', false, NULL,"
                        + " false, '{vat.registered}', ?, '2026-10-01T00:00:00Z', '2026-10-01T00:00:00Z')",
                alpha.id(),
                earlier,
                salons.defaultBranch(alpha),
                UUID.randomUUID());

        Map<String, Object> view = api.body(api.call(alpha, "GET", TAX_SETTINGS, null, manager));
        assertThat(CatalogueFixture.map(view.get("configured")).get("vatRatePercent"))
                .isEqualTo(15);
        assertThat(CatalogueFixture.map(
                        api.body(api.call(alpha, "GET", TAX, null, null)).get("vat")))
                .containsEntry("ratePercent", 18);

        Map<String, Object> saved =
                api.body(api.call(alpha, "PUT", TAX_SETTINGS, tax(1, "LK-9", null, false), manager));
        assertThat(saved.get("version")).isEqualTo(2);
        assertThat(CatalogueFixture.map(
                        api.body(api.call(alpha, "GET", TAX, null, null)).get("vat")))
                .containsEntry("ratePercent", 15);
        assertThat(CatalogueFixture.maps(api.body(api.call(alpha, "GET", TAX_HISTORY, null, manager))
                                .get("items"))
                        .getFirst()
                        .get("changedFields"))
                .isEqualTo(List.of("vat.ratePercent"));
        assertThat(as(alpha, () -> taxSettings.version(earlier))
                        .orElseThrow()
                        .vat()
                        .ratePercent())
                .isEqualTo(18);
        refused(api.call(alpha, "PUT", TAX_SETTINGS, tax(2, "LK-9", null, false), manager), 409, "invalid_state");
    }
}
