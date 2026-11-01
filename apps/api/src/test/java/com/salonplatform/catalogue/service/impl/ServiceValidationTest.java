package com.salonplatform.catalogue.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.service.SalonServices;
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
        return new ServiceInput(name, CATEGORY, description, price, minutes, buffer, null, null, null);
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
        ServiceInput empty = new ServiceInput(null, null, null, null, null, null, null, null, null);
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

    private static ServiceInput withVariants(SalonServices.VariantsInput variants, SalonServices.DepositInput deposit) {
        return new ServiceInput("Cut", CATEGORY, null, null, null, 10, null, variants, deposit);
    }

    private static SalonServices.VariantInput item(String name, long price, int minutes) {
        return new SalonServices.VariantInput(null, name, price, minutes);
    }

    @Test
    void variantsTakeThePlaceOfThePriceAndTimeAndKeepTheirOrder() {
        ServiceValidation.Valid valid = ServiceValidation.valid(
                withVariants(
                        new SalonServices.VariantsInput(
                                "  Hair length ",
                                java.util.List.of(item(" Long ", 400_000, 60), item("Short", 250_000, 30))),
                        null),
                RUPEES,
                false);

        assertThat(valid.priceMinor()).isNull();
        assertThat(valid.durationMinutes()).isNull();
        assertThat(valid.variants().name()).isEqualTo("Hair length");
        assertThat(valid.variants().items())
                .extracting(ServiceValidation.ValidVariant::name)
                .containsExactly("Long", "Short");
        assertThat(valid.deposit()).isEqualTo(new com.salonplatform.catalogue.ServiceDeposit.SalonRule());
    }

    @Test
    void aVariantNameIsUniqueInItsSetRegardlessOfCase() {
        assertThat(refusedField(withVariants(
                        new SalonServices.VariantsInput(
                                "Size", java.util.List.of(item("Short", 250_000, 30), item("SHORT ", 260_000, 30))),
                        null)))
                .isEqualTo("variants.items[1].name");
    }

    @Test
    void anAmountDepositIsAtMostTheLowestVariantPrice() {
        SalonServices.VariantsInput variants = new SalonServices.VariantsInput(
                "Size", java.util.List.of(item("S", 150_000, 30), item("L", 400_000, 60)));
        assertThat(refusedField(withVariants(variants, new SalonServices.DepositInput("amount", null, 150_100L))))
                .isEqualTo("deposit.amountMinor");
        assertThat(ServiceValidation.valid(
                                withVariants(variants, new SalonServices.DepositInput("amount", null, 150_000L)),
                                RUPEES,
                                false)
                        .deposit())
                .isEqualTo(new com.salonplatform.catalogue.ServiceDeposit.Amount(150_000));
    }
}
