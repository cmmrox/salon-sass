package com.salonplatform.catalogue.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.catalogue.TaxSettingsVersion.ServiceCharge;
import com.salonplatform.catalogue.TaxSettingsVersion.Vat;
import com.salonplatform.catalogue.config.CatalogueSettings;
import com.salonplatform.catalogue.service.TaxSettingsService.ServiceChargeInput;
import com.salonplatform.catalogue.service.TaxSettingsService.VatInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tax settings checked against F05-S03 AC-2, each broken rule naming its field, with the configured copies. */
class TaxValidationTest {

    private static final CatalogueSettings.Tax SRI_LANKA = new CatalogueSettings.Tax(
            new CatalogueSettings.Vat("VAT", 18, "VAT registration number"),
            new CatalogueSettings.ServiceCharge("Service charge", 20));
    private static final VatInput VAT_OFF = new VatInput(false, null);
    private static final ServiceChargeInput CHARGE_OFF = new ServiceChargeInput(false, null, false);

    private static List<String> refused(VatInput vat, ServiceChargeInput charge) {
        return assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> TaxValidation.valid(vat, charge, SRI_LANKA))
                .actual()
                .errors()
                .stream()
                .map(FieldProblem::field)
                .toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "AB-1234-5678-90-XYZ1", "lk-0001", "---"})
    void aRegistrationNumberOfOneToTwentyLettersDigitsAndHyphensIsKeptWithTheConfiguredNameAndRate(String number) {
        assertThat(TaxValidation.valid(new VatInput(true, number), CHARGE_OFF, SRI_LANKA)
                        .vat())
                .isEqualTo(new Vat("VAT", 18, true, number));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A12345678901234567890", "AB 12", "AB_12", "ÄB12", " AB12", "AB12\n"})
    void anyOtherRegistrationNumberIsRefused(String number) {
        assertThat(refused(new VatInput(true, number), CHARGE_OFF)).containsExactly("vat.registrationNumber");
    }

    @Test
    void vatOnWithoutANumberIsRefusedAndVatOffTakesNone() {
        assertThat(refused(new VatInput(true, null), CHARGE_OFF)).containsExactly("vat.registrationNumber");
        assertThat(refused(new VatInput(false, "LK-1"), CHARGE_OFF)).containsExactly("vat.registrationNumber");
        assertThat(TaxValidation.valid(VAT_OFF, CHARGE_OFF, SRI_LANKA).vat())
                .isEqualTo(new Vat("VAT", 18, false, null));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10, 20})
    void aServiceChargeOfOneToTheMaximumIsKept(int percent) {
        assertThat(TaxValidation.valid(VAT_OFF, new ServiceChargeInput(true, percent, true), SRI_LANKA)
                        .serviceCharge())
                .isEqualTo(new ServiceCharge("Service charge", true, percent, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 21, -1})
    void aServiceChargeOutsideOneToTheMaximumIsRefused(int percent) {
        assertThat(refused(VAT_OFF, new ServiceChargeInput(true, percent, false)))
                .containsExactly("serviceCharge.percent");
    }

    @Test
    void aServiceChargeOffTakesNoRateAndIsNotOnProducts() {
        assertThat(refused(VAT_OFF, new ServiceChargeInput(true, null, false)))
                .containsExactly("serviceCharge.percent");
        assertThat(refused(VAT_OFF, new ServiceChargeInput(false, 10, false))).containsExactly("serviceCharge.percent");
        assertThat(refused(VAT_OFF, new ServiceChargeInput(false, null, true)))
                .containsExactly("serviceCharge.onProducts");
        assertThat(TaxValidation.valid(VAT_OFF, CHARGE_OFF, SRI_LANKA).serviceCharge())
                .isEqualTo(new ServiceCharge("Service charge", false, 0, false));
    }

    @Test
    void missingPartsAreEachNamed() {
        assertThat(refused(null, null)).containsExactly("vat", "serviceCharge");
        assertThat(refused(new VatInput(null, null), new ServiceChargeInput(null, null, null)))
                .containsExactly("vat.registered", "serviceCharge.on");
        assertThat(refused(VAT_OFF, new ServiceChargeInput(true, 10, null)))
                .containsExactly("serviceCharge.onProducts");
    }
}
