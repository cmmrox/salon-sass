package com.salonplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Prices in whole units of the salon's currency, the rupee limits scaled by its fraction digits (decision 091). */
class MinorUnitsTest {

    @Test
    void rupeesHaveAHundredCentsAndYenNone() {
        assertThat(MinorUnits.of("LKR").minor(100)).isEqualTo(10_000);
        assertThat(MinorUnits.of("JPY").minor(100)).isEqualTo(100);
        assertThat(MinorUnits.of("XXZ").factor()).isEqualTo(1);
    }

    @Test
    void onlyWholeUnitsAreWhole() {
        assertThat(MinorUnits.of("LKR").whole(250_000)).isTrue();
        assertThat(MinorUnits.of("LKR").whole(250_050)).isFalse();
    }
}
