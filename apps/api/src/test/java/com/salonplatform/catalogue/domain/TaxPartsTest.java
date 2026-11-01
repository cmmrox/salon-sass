package com.salonplatform.catalogue.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.catalogue.PriceParts;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The inclusive arithmetic of F05-S03 AC-4 (decision 095), against an oracle written separately with
 * {@link BigDecimal}: over every price from Rs. 1 to Rs. 10,000 in Rs. 1 steps, for every service charge rate from 1
 * to 20% and with none, with VAT at 18% and without, the parts are the rule's and add up to the price exactly. And
 * the prototype's figures. The cases in {@code catalogue/tax-parts-cases.json} hold the web's live preview to the same
 * rule (its {@code tax-parts.test.ts} reads them too).
 */
class TaxPartsTest {

    private static final int VAT = 18;

    /** {@code amount / (1 + percent / 100)}, half to even, by decimal division: the oracle. */
    private static long removed(long amount, int percent) {
        return BigDecimal.valueOf(amount)
                .divide(BigDecimal.ONE.add(BigDecimal.valueOf(percent).movePointLeft(2)), 0, RoundingMode.HALF_EVEN)
                .longValueExact();
    }

    @Test
    void everyPriceFromOneRupeeToTenThousandForEveryRateAddsUpAndFollowsTheRule() {
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int vat : new int[] {0, VAT}) {
            for (int charge = 0; charge <= 20; charge++) {
                for (long rupees = 1; rupees <= 10_000; rupees++) {
                    long price = rupees * 100;
                    PriceParts parts = TaxParts.of(price, vat, charge);
                    long beforeVat = vat == 0 ? price : removed(price, vat);
                    long net = charge == 0 ? beforeVat : removed(beforeVat, charge);
                    PriceParts expected = new PriceParts(price, net, beforeVat - net, price - beforeVat);
                    checked++;
                    if (!parts.equals(expected)
                            || parts.netMinor() + parts.serviceChargeMinor() + parts.vatMinor() != price) {
                        wrong.add(vat + "% / " + charge + "% of " + price + ": " + parts + " not " + expected);
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(2 * 21 * 10_000);
        assertThat(wrong).isEmpty();
    }

    @Test
    void thePrototypesFiguresAreKept() {
        // Rs. 2,500 with a 10% service charge and 18% VAT: service charge Rs. 192.60, VAT Rs. 381.36.
        assertThat(TaxParts.of(250_000, VAT, 10)).isEqualTo(new PriceParts(250_000, 192_604, 19_260, 38_136));
        // Rs. 3,200 with VAT only: VAT Rs. 488.14.
        assertThat(TaxParts.of(320_000, VAT, 0)).isEqualTo(new PriceParts(320_000, 271_186, 0, 48_814));
        // Nothing applies: all of it is net.
        assertThat(TaxParts.of(250_000, 0, 0)).isEqualTo(new PriceParts(250_000, 250_000, 0, 0));
    }

    @Test
    void theCasesTheWebsPreviewIsHeldToAreTheRules() throws IOException {
        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/catalogue/tax-parts-cases.json")) {
            cases = JsonMapper.builder().build().readTree(in).get("cases");
        }
        assertThat(cases.size()).isGreaterThan(100);
        for (JsonNode each : cases) {
            assertThat(TaxParts.of(
                            each.get("priceMinor").asLong(),
                            each.get("vatPercent").asInt(),
                            each.get("serviceChargePercent").asInt()))
                    .as(each.toString())
                    .isEqualTo(new PriceParts(
                            each.get("priceMinor").asLong(),
                            each.get("netMinor").asLong(),
                            each.get("serviceChargeMinor").asLong(),
                            each.get("vatMinor").asLong()));
        }
    }

    @ParameterizedTest(name = "{0} / {1} rounds to {2}")
    @CsvSource({"5, 2, 2", "7, 2, 4", "9, 2, 4", "11, 2, 6", "10, 4, 2", "14, 4, 4", "13, 4, 3", "0, 7, 0"})
    void roundingIsHalfToEven(long dividend, long divisor, long quotient) {
        assertThat(TaxParts.halfEven(dividend, divisor)).isEqualTo(quotient);
    }

    @Test
    void aNegativePriceOrRateIsNotAPrice() {
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> TaxParts.of(-1, VAT, 10));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> TaxParts.of(100, -1, 10));
    }
}
