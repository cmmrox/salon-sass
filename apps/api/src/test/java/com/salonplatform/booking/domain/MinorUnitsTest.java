package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MinorUnitsTest {

    @Test
    void rupeesHaveAHundredCentsAndAWholeAmountIsAMultipleOfThem() {
        MinorUnits rupees = MinorUnits.of("LKR");
        assertThat(rupees.minor(500)).isEqualTo(50_000);
        assertThat(rupees.whole(50_000)).isTrue();
        assertThat(rupees.whole(50_050)).isFalse();
    }

    @Test
    void theFactorFollowsTheCurrencysFractionDigits() {
        assertThat(MinorUnits.of("JPY").factor()).isEqualTo(1);
        assertThat(MinorUnits.of("KWD").factor()).isEqualTo(1_000);
        assertThat(MinorUnits.of("XXX").factor()).isEqualTo(1);
    }
}
