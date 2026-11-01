package com.salonplatform.catalogue.entity;

import com.salonplatform.catalogue.SegmentKind;
import com.salonplatform.catalogue.ServiceSegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Ordered segments as a terms version and a variant store them: two arrays read position by position. */
final class StoredSegments {

    private StoredSegments() {}

    static String[] kinds(List<ServiceSegment> segments) {
        return segments.stream()
                .map(segment -> segment.kind().name().toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
    }

    static Integer[] minutes(List<ServiceSegment> segments) {
        return segments.stream().map(ServiceSegment::minutes).toArray(Integer[]::new);
    }

    static List<ServiceSegment> of(String[] kinds, Integer[] minutes) {
        List<ServiceSegment> segments = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            segments.add(new ServiceSegment(SegmentKind.valueOf(kinds[i].toUpperCase(Locale.ROOT)), minutes[i]));
        }
        return List.copyOf(segments);
    }
}
