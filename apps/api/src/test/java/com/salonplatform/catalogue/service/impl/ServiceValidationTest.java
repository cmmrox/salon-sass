package com.salonplatform.catalogue.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.service.SalonServices.ServiceInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.FieldProblem;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** A service's fields checked against the prototype's limits (F05-S01 AC-3), each broken rule naming its field. */
class ServiceValidationTest {

    private static final MinorUnits RUPEES = MinorUnits.of("LKR");
    private static final UUID CATEGORY = UUID.randomUUID();

    private static ServiceInput input(String name, Long price, Integer minutes, Integer buffer, String description) {
        return new ServiceInput(name, CATEGORY, description, price, minutes, buffer, null);
    }

    private static String refusedField(ServiceInput input) {
        return assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> ServiceValidation.valid(input, RUPEES, false))
                .actual()
                .errors()
                .stream()
                .map(FieldProblem::field)
                .reduce((a, b) -> a + "," + b)
                .orElseThrow();
    }

    @ParameterizedTest
    @CsvSource({
        "B, 250000, 45, 0, name",
        "'  B  ', 250000, 45, 0, name",
        "Forty-one characters are too long for it!, 250000, 45, 0, name",
        "Ok, 9900, 45, 0, priceMinor",
        "Ok, 50000100, 45, 0, priceMinor",
        "Ok, 250050, 45, 0, priceMinor",
        "Ok, 250000, 0, 0, durationMinutes",
        "Ok, 250000, 7, 0, durationMinutes",
        "Ok, 250000, 485, 0, durationMinutes",
        "Ok, 250000, 45, 65, bufferMinutes",
        "Ok, 250000, 45, 3, bufferMinutes",
        "Ok, 250000, 45, -5, bufferMinutes"
    })
    void eachBoundaryIsRefusedOnItsField(String name, long price, int minutes, int buffer, String field) {
        assertThat(refusedField(input(name, price, minutes, buffer, null))).isEqualTo(field);
    }

    @Test
    void theEdgesOfEveryRangeAreAccepted() {
        assertThat(ServiceValidation.valid(input("Ab", 10_000L, 5, 0, "d".repeat(90)), RUPEES, false))
                .satisfies(valid -> assertThat(valid.description()).hasSize(90));
        assertThat(ServiceValidation.valid(input("n".repeat(40), 50_000_000L, 480, 60, null), RUPEES, false)
                        .bufferMinutes())
                .isEqualTo(60);
    }

    @Test
    void aDescriptionOfNinetyOneIsRefusedAndAnEmptyOneIsNone() {
        assertThat(refusedField(input("Ok", 250_000L, 45, 0, "d".repeat(91)))).isEqualTo("description");
        assertThat(ServiceValidation.valid(input("Ok", 250_000L, 45, 0, "   "), RUPEES, false)
                        .description())
                .isNull();
    }

    @Test
    void missingValuesAreEachNamedAndOnlineIsRequiredOnlyOnASave() {
        ServiceInput empty = new ServiceInput(null, null, null, null, null, null, null);
        assertThat(refusedField(empty)).isEqualTo("name,categoryId,priceMinor,durationMinutes,bufferMinutes");
        assertThat(ServiceValidation.valid(input("Ok", 250_000L, 45, 0, null), RUPEES, false)
                        .bookableOnline())
                .isTrue();
        assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> ServiceValidation.valid(input("Ok", 250_000L, 45, 0, null), RUPEES, true))
                .satisfies(refused -> assertThat(refused.errors())
                        .extracting(FieldProblem::field)
                        .containsExactly("bookableOnline"));
    }

    @Test
    void theRupeeLimitsScaleForACurrencyWithoutCents() {
        MinorUnits yen = MinorUnits.of("JPY");
        assertThat(ServiceValidation.valid(input("Ok", 100L, 45, 0, null), yen, false)
                        .priceMinor())
                .isEqualTo(100);
        assertThatExceptionOfType(ApplicationProblemException.class)
                .isThrownBy(() -> ServiceValidation.valid(input("Ok", 99L, 45, 0, null), yen, false));
    }
}
