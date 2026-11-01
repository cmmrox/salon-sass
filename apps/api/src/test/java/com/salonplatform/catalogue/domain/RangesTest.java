package com.salonplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.ServiceVariant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A service's lowest and highest price and customer's time (F05-S02 AC-3). */
class RangesTest {

    @Test
    void withoutVariantsTheRangeIsTheServicesOwnPriceAndTime() {
        assertThat(Ranges.of(250_000, Segments.of(45, 10), List.of())).isEqualTo(new Ranges(250_000, 250_000, 45, 45));
    }

    @Test
    void withVariantsTheRangeIsTheirsAndTheBufferIsNotTheCustomersTime() {
        List<ServiceVariant> variants = List.of(
                new ServiceVariant(UUID.randomUUID(), "Medium", 320_000, Segments.of(45, 10)),
                new ServiceVariant(UUID.randomUUID(), "Short", 250_000, Segments.of(60, 10)),
                new ServiceVariant(UUID.randomUUID(), "Long", 400_000, Segments.of(30, 10)));

        assertThat(Ranges.of(250_000, Segments.of(30, 10), variants)).isEqualTo(new Ranges(250_000, 400_000, 30, 60));
    }
}
