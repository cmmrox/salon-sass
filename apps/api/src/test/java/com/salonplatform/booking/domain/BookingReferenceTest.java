package com.salonplatform.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** References (BKG-14, decision 108(d)). */
class BookingReferenceTest {

    @Test
    void aReferenceIsSixCharactersOfTheUnambiguousAlphabet() {
        assertThat(BookingReference.ALPHABET)
                .doesNotContain("0", "O", "1", "I", "L")
                .hasSize(31);
        Random random = new Random(7);
        Set<String> drawn = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            String reference = BookingReference.draw(random);
            assertThat(reference).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}");
            drawn.add(reference);
        }
        assertThat(drawn).hasSizeGreaterThan(1990);
    }

    @Test
    void itIsShownWithAHyphenAfterTheThirdCharacter() {
        assertThat(BookingReference.shown("K7M4QX")).isEqualTo("K7M-4QX");
    }

    @Test
    void aSearchMatchesItWithOrWithoutTheHyphenWhateverTheCase() {
        assertThat(BookingReference.searched("K7M-4QX")).contains("K7M4QX");
        assertThat(BookingReference.searched(" k7m4qx ")).contains("K7M4QX");
        assertThat(BookingReference.searched("k7m-4qx")).contains("K7M4QX");
    }

    @Test
    void textThatCannotBeAReferenceSearchesNone() {
        assertThat(BookingReference.searched("K7M-4Q")).isEmpty();
        assertThat(BookingReference.searched("K7M4QX9")).isEmpty();
        assertThat(BookingReference.searched("K0M4QX")).isEmpty();
        assertThat(BookingReference.searched("Nimali")).isEmpty();
        assertThat(BookingReference.searched("K7M_4QX")).isEmpty();
    }
}
