package com.salonplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** A duplicate's name (F05-S01 AC-7): "(copy)", then "(copy 2)" and upward, the base shortened to fit 40. */
class CopyNamesTest {

    @Test
    void theFirstCopyIsCalledCopy() {
        assertThat(CopyNames.of("Balayage", Set.of("balayage"))).isEqualTo("Balayage (copy)");
    }

    @Test
    void aTakenCopyNameInAnyCaseMovesToTheNextNumber() {
        assertThat(CopyNames.of("Balayage", Set.of("balayage", "balayage (copy)", "balayage (copy 2)")))
                .isEqualTo("Balayage (copy 3)");
    }

    @Test
    void theBaseIsShortenedSoTheNameFitsFortyCharactersWithoutATrailingSpace() {
        String forty = "Extra long hair treatment with argan oil";

        String first = CopyNames.of(forty, Set.of());
        String second = CopyNames.of(forty, Set.of(first.toLowerCase()));

        assertThat(first).isEqualTo("Extra long hair treatment with ar (copy)").hasSize(40);
        assertThat(second).isEqualTo("Extra long hair treatment with (copy 2)").hasSize(39);
    }

    @Test
    void theBaseIsCountedAndCutInCharactersSoAnEmojiIsNeverSplit() {
        // 32 letters and spaces, an emoji (one character, two UTF-16 units), then 7 more: 40 characters.
        String name = "Keratin smoothing with blow dry \uD83D\uDC87 deluxe";
        assertThat(name.codePointCount(0, name.length())).isEqualTo(40);

        String first = CopyNames.of(name, Set.of());
        String second = CopyNames.of(name, Set.of(first.toLowerCase()));

        assertThat(first).isEqualTo("Keratin smoothing with blow dry \uD83D\uDC87 (copy)");
        assertThat(first.codePointCount(0, first.length())).isEqualTo(40);
        assertThat(second).isEqualTo("Keratin smoothing with blow dry (copy 2)");
    }

    @Test
    void aNameThatFitsInCharactersIsNotShortenedForItsUtf16Length() {
        // 33 characters, the last six emoji: 39 UTF-16 units, but with " (copy)" exactly 40 characters.
        String name = "Bridal glow facial package " + "\uD83D\uDC70\uD83C\uDF38".repeat(3);
        assertThat(name.codePointCount(0, name.length())).isEqualTo(33);

        assertThat(CopyNames.of(name, Set.of())).isEqualTo(name + " (copy)");
    }
}
