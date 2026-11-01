package com.salonplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A service's time as ordered segments (decision 074, F05-S01 AC-5). */
class SegmentsTest {

    @Test
    void theTimeIsAnActiveSegmentThenABufferWhenThereIsOne() {
        assertThat(Segments.of(45, 10))
                .containsExactly(
                        new ServiceSegment(SegmentKind.ACTIVE, 45), new ServiceSegment(SegmentKind.BUFFER, 10));
        assertThat(Segments.of(45, 0)).containsExactly(new ServiceSegment(SegmentKind.ACTIVE, 45));
    }

    @Test
    void theCustomersTimeIsEverySegmentButTheBuffer() {
        List<ServiceSegment> segments = List.of(
                new ServiceSegment(SegmentKind.ACTIVE, 30),
                new ServiceSegment(SegmentKind.PROCESSING, 20),
                new ServiceSegment(SegmentKind.ACTIVE, 15),
                new ServiceSegment(SegmentKind.BUFFER, 10));

        assertThat(Segments.duration(segments)).isEqualTo(65);
        assertThat(Segments.buffer(segments)).isEqualTo(10);
    }
}
