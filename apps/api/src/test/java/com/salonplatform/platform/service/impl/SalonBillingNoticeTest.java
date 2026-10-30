package com.salonplatform.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** The workspace banner the API decides with its own clock (AC-12). */
class SalonBillingNoticeTest {

    private static final Instant TRIAL_END = Instant.parse("2026-11-01T09:00:00Z");

    @Test
    void aTrialShowsTheBannerFromThreeDaysBeforeItEndsAndOverdueStatesAlways() {
        assertThat(SalonBillingServiceImpl.notice("trialing", TRIAL_END, TRIAL_END.minusSeconds(3 * 86400 + 1)))
                .isNull();
        assertThat(SalonBillingServiceImpl.notice("trialing", TRIAL_END, TRIAL_END.minusSeconds(3 * 86400)))
                .isEqualTo("trial_ending");
        assertThat(SalonBillingServiceImpl.notice("active", TRIAL_END, TRIAL_END))
                .isNull();
        assertThat(SalonBillingServiceImpl.notice("past_due", TRIAL_END, TRIAL_END))
                .isEqualTo("past_due");
        assertThat(SalonBillingServiceImpl.notice("read_only", TRIAL_END, TRIAL_END))
                .isEqualTo("read_only");
    }
}
