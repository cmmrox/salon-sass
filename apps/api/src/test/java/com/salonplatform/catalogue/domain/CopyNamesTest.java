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
}
