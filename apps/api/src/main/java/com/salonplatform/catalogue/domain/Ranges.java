package com.salonplatform.catalogue.domain;

import com.salonplatform.catalogue.ServiceSegment;
import com.salonplatform.catalogue.ServiceVariant;
import java.util.List;

/**
 * A service's lowest and highest price and customer's time (F05-S02 AC-3): its own without variants, its variants'
 * with them. The customer's time is every segment but the buffer.
 */
public record Ranges(long priceMinMinor, long priceMaxMinor, int durationMinMinutes, int durationMaxMinutes) {

    public static Ranges of(long priceMinor, List<ServiceSegment> segments, List<ServiceVariant> variants) {
        if (variants.isEmpty()) {
            int duration = Segments.duration(segments);
            return new Ranges(priceMinor, priceMinor, duration, duration);
        }
        long priceMin = Long.MAX_VALUE;
        long priceMax = Long.MIN_VALUE;
        int durationMin = Integer.MAX_VALUE;
        int durationMax = Integer.MIN_VALUE;
        for (ServiceVariant variant : variants) {
            int duration = Segments.duration(variant.segments());
            priceMin = Math.min(priceMin, variant.priceMinor());
            priceMax = Math.max(priceMax, variant.priceMinor());
            durationMin = Math.min(durationMin, duration);
            durationMax = Math.max(durationMax, duration);
        }
        return new Ranges(priceMin, priceMax, durationMin, durationMax);
    }
}
