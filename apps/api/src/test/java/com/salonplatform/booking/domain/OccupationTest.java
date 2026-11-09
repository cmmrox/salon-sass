package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What a booking occupies (decision 074, AC-4). */
class OccupationTest {

    private static final Instant START = Instant.parse("2086-06-15T04:30:00Z");

    @Test
    void anActiveSegmentAndItsBufferOccupyOneRangeFromTheStartToTheEnd() {
        Occupation cut = Occupation.of(
                START, List.of(new ServiceSegment(SegmentKind.ACTIVE, 45), new ServiceSegment(SegmentKind.BUFFER, 15)));

        assertThat(cut.startsAt()).isEqualTo(START);
        assertThat(cut.endsAt()).isEqualTo(Instant.parse("2086-06-15T05:30:00Z"));
        assertThat(cut.literal()).isEqualTo("{[\"2086-06-15T04:30:00Z\",\"2086-06-15T05:30:00Z\")}");
    }

    @Test
    void aProcessingSegmentOccupiesNoProfessionalSoTheTimeSplitsInTwo() {
        Occupation colour = Occupation.of(
                START,
                List.of(
                        new ServiceSegment(SegmentKind.ACTIVE, 30),
                        new ServiceSegment(SegmentKind.PROCESSING, 40),
                        new ServiceSegment(SegmentKind.ACTIVE, 20),
                        new ServiceSegment(SegmentKind.BUFFER, 10)));

        assertThat(colour.endsAt()).isEqualTo(Instant.parse("2086-06-15T06:10:00Z"));
        assertThat(colour.literal())
                .isEqualTo("{[\"2086-06-15T04:30:00Z\",\"2086-06-15T05:00:00Z\"),"
                        + "[\"2086-06-15T05:40:00Z\",\"2086-06-15T06:10:00Z\")}");
    }

    @Test
    void aBookingMustOccupyItsProfessionalForSomeOfItsTime() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Occupation.of(START, List.of(new ServiceSegment(SegmentKind.PROCESSING, 30))));
        assertThatIllegalArgumentException().isThrownBy(() -> Occupation.of(START, List.of()));
    }
}
