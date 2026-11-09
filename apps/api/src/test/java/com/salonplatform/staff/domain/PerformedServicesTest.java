package com.salonplatform.staff.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.staff.domain.PerformedServices.Choice;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a person performs, worked out from what is held and what the catalogue lists now (F06-S01 AC-3). */
class PerformedServicesTest {

    private static final UUID COLOUR = UUID.randomUUID();
    private static final UUID NAILS = UUID.randomUUID();
    private static final UUID TINT = UUID.randomUUID();
    private static final UUID GLOSS = UUID.randomUUID();
    private static final UUID GEL = UUID.randomUUID();

    private static final CatalogueListing LISTING = new CatalogueListing(
            List.of(new CatalogueListing.Category(COLOUR, "Colour"), new CatalogueListing.Category(NAILS, "Nails")),
            List.of(
                    new CatalogueListing.Service(GEL, NAILS, "Gel", true),
                    new CatalogueListing.Service(GLOSS, COLOUR, "Gloss", false),
                    new CatalogueListing.Service(TINT, COLOUR, "Tint", true)));

    @Test
    void aWholeCategoryPerformsEveryServiceInIt() {
        assertThat(PerformedServices.performed(LISTING, new Choice(Set.of(COLOUR), Set.of())))
                .containsExactly(GLOSS, TINT);
    }

    @Test
    void aSingleServicePerformsOnlyThatService() {
        assertThat(PerformedServices.performed(LISTING, new Choice(Set.of(), Set.of(TINT))))
                .containsExactly(TINT);
    }

    @Test
    void wholeCategoriesAndSingleServicesAddUp() {
        assertThat(PerformedServices.performed(LISTING, new Choice(Set.of(NAILS), Set.of(TINT))))
                .containsExactly(GEL, TINT);
    }

    @Test
    void aStaleIdIsDroppedWhenRead() {
        Choice read =
                PerformedServices.current(LISTING, List.of(COLOUR, UUID.randomUUID()), List.of(UUID.randomUUID()));

        assertThat(read).isEqualTo(new Choice(Set.of(COLOUR), Set.of()));
    }

    @Test
    void aSingleServiceThatHasMovedIntoAWholeCategoryIsNotAlsoSingleWhenRead() {
        Choice read = PerformedServices.current(LISTING, List.of(COLOUR), List.of(TINT, GEL));

        assertThat(read).isEqualTo(new Choice(Set.of(COLOUR), Set.of(GEL)));
    }

    @Test
    void aChoiceOfTheSalonsOwnIdsWithoutOverlapIsAcceptable() {
        assertThat(PerformedServices.acceptable(LISTING, List.of(NAILS), List.of(TINT)))
                .isTrue();
        assertThat(PerformedServices.acceptable(LISTING, List.of(), List.of())).isTrue();
    }

    @Test
    void anUnknownIdOrAnOverlapIsNotAcceptable() {
        assertThat(PerformedServices.acceptable(LISTING, List.of(UUID.randomUUID()), List.of()))
                .isFalse();
        assertThat(PerformedServices.acceptable(LISTING, List.of(), List.of(UUID.randomUUID())))
                .isFalse();
        assertThat(PerformedServices.acceptable(LISTING, List.of(COLOUR), List.of(TINT)))
                .isFalse();
    }
}
