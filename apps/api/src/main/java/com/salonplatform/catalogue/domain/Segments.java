package com.salonplatform.catalogue.domain;

import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import java.util.ArrayList;
import java.util.List;

/**
 * A service's time as ordered segments (decision 074, F05-S01 AC-5): an active segment of the service's time, then
 * a buffer segment when the buffer is above zero. R1 screens edit time and buffer only, so nothing here makes a
 * processing segment.
 */
public final class Segments {

    private Segments() {}

    public static List<ServiceSegment> of(int durationMinutes, int bufferMinutes) {
        List<ServiceSegment> segments = new ArrayList<>();
        segments.add(new ServiceSegment(SegmentKind.ACTIVE, durationMinutes));
        if (bufferMinutes > 0) {
            segments.add(new ServiceSegment(SegmentKind.BUFFER, bufferMinutes));
        }
        return List.copyOf(segments);
    }

    /** The service's own time: every segment but the buffer. */
    public static int duration(List<ServiceSegment> segments) {
        return segments.stream()
                .filter(segment -> segment.kind() != SegmentKind.BUFFER)
                .mapToInt(ServiceSegment::minutes)
                .sum();
    }

    /** The buffer after the service: 0 when it has none. */
    public static int buffer(List<ServiceSegment> segments) {
        return segments.stream()
                .filter(segment -> segment.kind() == SegmentKind.BUFFER)
                .mapToInt(ServiceSegment::minutes)
                .sum();
    }
}
