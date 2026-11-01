package com.salonplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.TaxSettingsVersion.ServiceCharge;
import com.salonplatform.catalogue.TaxSettingsVersion.Vat;
import org.junit.jupiter.api.Test;

/** Which tax settings a save changes (F05-S03 AC-3), named as the API names them, in the tab's order. */
class TaxChangesTest {

    private static final Vat OFF = new Vat("VAT", 18, false, null);
    private static final ServiceCharge NONE = new ServiceCharge("Service charge", false, 0, false);

    @Test
    void theFirstVersionChangesEverySetting() {
        assertThat(TaxChanges.between(null, null, OFF, NONE)).isEqualTo(TaxChanges.FIELDS);
    }

    @Test
    void anIdenticalSaveChangesNothing() {
        assertThat(TaxChanges.between(OFF, NONE, new Vat("VAT", 18, false, null), NONE))
                .isEmpty();
    }

    @Test
    void eachChangeIsNamedInTheTabsOrder() {
        Vat registered = new Vat("VAT", 18, true, "LK-1");
        ServiceCharge ten = new ServiceCharge("Service charge", true, 10, true);
        assertThat(TaxChanges.between(OFF, NONE, registered, ten))
                .containsExactly(
                        "vat.registered",
                        "vat.registrationNumber",
                        "serviceCharge.on",
                        "serviceCharge.percent",
                        "serviceCharge.onProducts");
    }

    @Test
    void aChangeOfConfigurationAloneIsNamedToo() {
        assertThat(TaxChanges.between(OFF, NONE, new Vat("VAT", 15, false, null), NONE))
                .containsExactly("vat.ratePercent");
        assertThat(TaxChanges.between(
                        OFF, NONE, new Vat("GST", 18, false, null), new ServiceCharge("Tip", false, 0, false)))
                .containsExactly("vat.name", "serviceCharge.name");
    }
}
