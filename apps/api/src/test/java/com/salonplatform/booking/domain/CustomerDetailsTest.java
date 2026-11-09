package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** A customer's name and mobile number on a booking (AC-2, decision 084). */
class CustomerDetailsTest {

    @Test
    void aNameIsTwoToSixtyCharactersOnceTrimmed() {
        assertThat(CustomerName.parse("A")).isEmpty();
        assertThat(CustomerName.parse("  A  ")).isEmpty();
        assertThat(CustomerName.parse("Al")).contains("Al");
        assertThat(CustomerName.parse("  Nimali Perera ")).contains("Nimali Perera");
        assertThat(CustomerName.parse("x".repeat(60))).isPresent();
        assertThat(CustomerName.parse("x".repeat(61))).isEmpty();
        assertThat(CustomerName.parse(null)).isEmpty();
    }

    @Test
    void charactersAreCountedAsPeopleSeeThemNotAsJavaStoresThem() {
        String blossoms = "🌸".repeat(60); // 60 blossoms, 120 UTF-16 units.
        assertThat(CustomerName.parse(blossoms)).isPresent();
    }

    @Test
    void aSriLankanMobileInAnyCommonFormIsStoredInE164AndShownNationally() {
        for (String typed : new String[] {"077 123 4567", "0771234567", "+94771234567", "94 77 123 4567"}) {
            assertThat(CustomerMobile.parse(typed, "LK")).contains("+94771234567");
        }
        assertThat(CustomerMobile.national("+94771234567")).isEqualTo("077 123 4567");
    }

    @Test
    void aLandlineAForeignNumberAndTextAreNotMobilesOfTheRegion() {
        assertThat(CustomerMobile.parse("011 234 5678", "LK")).isEmpty();
        assertThat(CustomerMobile.parse("+44 7700 900123", "LK")).isEmpty();
        assertThat(CustomerMobile.parse("not a number", "LK")).isEmpty();
        assertThat(CustomerMobile.parse("", "LK")).isEmpty();
        assertThat(CustomerMobile.parse(null, "LK")).isEmpty();
    }
}
