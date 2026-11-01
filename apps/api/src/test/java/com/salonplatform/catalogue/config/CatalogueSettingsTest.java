package com.salonplatform.catalogue.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Startup is refused for a tax rate outside 0 to 100 or an empty name (F05-S03, "Design notes"): the settings records
 * refuse them, and Spring Boot fails to bind {@code salon.catalogue.tax}.
 */
class CatalogueSettingsTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void aVatRateOutsideZeroToAHundredIsRefused(int rate) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CatalogueSettings.Vat("VAT", rate, "VAT registration number"))
                .withMessageContaining("rate-percent");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void aServiceChargeMaximumOutsideOneToAHundredIsRefused(int max) {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CatalogueSettings.ServiceCharge("Service charge", max))
                .withMessageContaining("max-percent");
    }

    @Test
    void anEmptyNameIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CatalogueSettings.Vat(" ", 18, "VAT registration number"))
                .withMessageContaining("vat.name");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CatalogueSettings.Vat("VAT", 18, ""))
                .withMessageContaining("registration-label");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new CatalogueSettings.ServiceCharge(null, 20))
                .withMessageContaining("service-charge.name");
    }

    @Test
    void theEdgesAreAccepted() {
        assertThat(new CatalogueSettings.Vat("VAT", 0, "VAT registration number").ratePercent())
                .isZero();
        assertThat(new CatalogueSettings.Vat("VAT", 100, "VAT registration number").ratePercent())
                .isEqualTo(100);
        assertThat(new CatalogueSettings.ServiceCharge("Service charge", 1).maxPercent())
                .isEqualTo(1);
    }
}
