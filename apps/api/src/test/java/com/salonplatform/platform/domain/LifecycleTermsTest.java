package com.salonplatform.platform.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class LifecycleTermsTest {

    @Test
    void aReasonIsTenToFiveHundredCharactersOfPlainText() {
        assertThat(LifecycleTerms.reason(null)).isNull();
        assertThat(LifecycleTerms.reason("   short   ")).isNull();
        assertThat(LifecycleTerms.reason("123456789")).isNull();
        assertThat(LifecycleTerms.reason("  1234567890  ")).isEqualTo("1234567890");
        assertThat(LifecycleTerms.reason("line one\nline two")).isEqualTo("line one\nline two");
        assertThat(LifecycleTerms.reason("x".repeat(500))).hasSize(500);
        assertThat(LifecycleTerms.reason("x".repeat(501))).isNull();
        assertThat(LifecycleTerms.reason("ten chars \u0007 bell")).isNull();
        // Counted in characters, not UTF-16 units: ten emoji are ten characters.
        assertThat(LifecycleTerms.reason("😀".repeat(10))).isNotNull();
    }

    @Test
    void dataIsDeletedExactlyThirtyDaysOfTwentyFourHoursAfterClosure() {
        Instant closed = Instant.parse("2026-03-28T23:30:00Z");
        assertThat(LifecycleTerms.deleteAt(closed)).isEqualTo(Instant.parse("2026-04-27T23:30:00Z"));
    }
}
