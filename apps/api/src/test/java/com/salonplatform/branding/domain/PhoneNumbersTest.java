package com.salonplatform.branding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Phone numbers are checked for the configured region and written once in E.164 (decision 084, NFR-13). */
class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource({
        "077 123 4567, +94771234567",
        "+94771234567, +94771234567",
        "0771234567, +94771234567",
        "+94 77 123 4567, +94771234567",
        "(077) 123-4567, +94771234567",
        "0112504417, +94112504417",
        "011 250 4417, +94112504417"
    })
    void everyCommonFormOfASriLankanNumberIsTheSameE164Number(String typed, String e164) {
        assertThat(PhoneNumbers.check(typed, "LK", false)).isEqualTo(new PhoneNumbers.Valid(e164));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "07712345", "077123456789", "+44 20 7946 0958", "not a number", "+9411"})
    void aNumberThatIsNotOneOfTheRegionsIsInvalid(String typed) {
        assertThat(PhoneNumbers.check(typed, "LK", false)).isEqualTo(new PhoneNumbers.Invalid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0112504417", "+94112504417"})
    void aLandlineIsNotAWhatsAppNumber(String typed) {
        assertThat(PhoneNumbers.check(typed, "LK", true)).isEqualTo(new PhoneNumbers.NotMobile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"077 412 3300", "+94774123300"})
    void aMobileNumberIsAWhatsAppNumber(String typed) {
        assertThat(PhoneNumbers.check(typed, "LK", true)).isEqualTo(new PhoneNumbers.Valid("+94774123300"));
    }

    @ParameterizedTest
    @CsvSource({"020 7946 0958, GB, +442079460958", "077 123 4567, GB, invalid"})
    void theRegionIsConfigurationNotCode(String typed, String region, String expected) {
        PhoneNumbers.Checked checked = PhoneNumbers.check(typed, region, false);
        if (expected.equals("invalid")) {
            assertThat(checked).isEqualTo(new PhoneNumbers.Invalid());
        } else {
            assertThat(checked).isEqualTo(new PhoneNumbers.Valid(expected));
        }
    }
}
