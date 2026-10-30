package com.salonplatform.shared.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The conversion between instants and a salon's local time, on a fixed clock. */
class SalonTimeTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    @Test
    void nowIsTheApplicationsClock() {
        Instant fixed = Instant.parse("2026-10-12T20:00:00Z");
        assertThat(new SalonTime(Clock.fixed(fixed, ZoneOffset.UTC)).now()).isEqualTo(fixed);
    }

    /** Colombo is five and a half hours ahead all year: no change of clocks. */
    @ParameterizedTest
    @CsvSource({
        "2026-10-12T20:00:00Z, 2026-10-13T01:30:00+05:30",
        "2026-01-01T00:00:00Z, 2026-01-01T05:30:00+05:30",
        "2026-06-30T18:29:59Z, 2026-06-30T23:59:59+05:30",
        "2026-06-30T18:30:00Z, 2026-07-01T00:00:00+05:30",
    })
    void colomboIsAlwaysFiveAndAHalfHoursAhead(String instant, String local) {
        assertThat(SalonTime.formatLocal(Instant.parse(instant), COLOMBO)).isEqualTo(local);
    }

    /** London in 2026: GMT until 01:00 UTC on 29 March, BST until 01:00 UTC on 25 October. */
    @ParameterizedTest
    @CsvSource({
        "2026-03-29T00:30:00Z, 2026-03-29T00:30:00+00:00",
        "2026-03-29T00:59:59Z, 2026-03-29T00:59:59+00:00",
        "2026-03-29T01:00:00Z, 2026-03-29T02:00:00+01:00",
        "2026-03-29T01:30:00Z, 2026-03-29T02:30:00+01:00",
        "2026-10-25T00:30:00Z, 2026-10-25T01:30:00+01:00",
        "2026-10-25T01:30:00Z, 2026-10-25T01:30:00+00:00",
        "2026-12-31T23:59:59Z, 2026-12-31T23:59:59+00:00",
    })
    void londonAcrossBothChangesOfClocks(String instant, String local) {
        assertThat(SalonTime.formatLocal(Instant.parse(instant), LONDON)).isEqualTo(local);
    }

    @Test
    void theOffsetIsAlwaysWrittenAsHoursAndMinutesNeverZ() {
        assertThat(SalonTime.formatLocal(Instant.parse("2026-01-15T10:00:00Z"), ZoneId.of("UTC")))
                .isEqualTo("2026-01-15T10:00:00+00:00");
    }

    @Test
    void theLocalTimeIsShownToTheSecond() {
        assertThat(SalonTime.formatLocal(Instant.parse("2026-01-15T10:00:00.987654Z"), LONDON))
                .isEqualTo("2026-01-15T10:00:00+00:00");
    }

    @Test
    void aLocalTimeInTheHourThatIsSkippedMovesForwardByTheGap() {
        // 01:30 on 29 March 2026 never happens in London: 01:00 GMT becomes 02:00 BST.
        Instant instant = SalonTime.toInstant(LocalDateTime.parse("2026-03-29T01:30:00"), LONDON);

        assertThat(instant).isEqualTo(Instant.parse("2026-03-29T01:30:00Z"));
        assertThat(SalonTime.formatLocal(instant, LONDON)).isEqualTo("2026-03-29T02:30:00+01:00");
    }

    @Test
    void aLocalTimeInTheHourThatRepeatsIsTheEarlierInstant() {
        // 01:30 on 25 October 2026 happens twice in London: first in BST, then in GMT.
        Instant instant = SalonTime.toInstant(LocalDateTime.parse("2026-10-25T01:30:00"), LONDON);

        assertThat(instant).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
    }

    @Test
    void anOrdinaryLocalTimeIsItsOneInstant() {
        assertThat(SalonTime.toInstant(LocalDateTime.parse("2026-10-13T01:30:00"), COLOMBO))
                .isEqualTo(Instant.parse("2026-10-12T20:00:00Z"));
        assertThat(SalonTime.toInstant(LocalDateTime.parse("2026-07-01T09:00:00"), LONDON))
                .isEqualTo(Instant.parse("2026-07-01T08:00:00Z"));
    }

    @Test
    void anInstantAndItsLocalTimeGoBothWaysOutsideTheChanges() {
        Instant instant = Instant.parse("2026-08-01T12:34:56Z");
        for (ZoneId zone : new ZoneId[] {COLOMBO, LONDON}) {
            assertThat(SalonTime.toInstant(SalonTime.toLocal(instant, zone).toLocalDateTime(), zone))
                    .isEqualTo(instant);
        }
    }
}
