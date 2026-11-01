package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.domain.CatalogueLimits.PRODUCT_NAME_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRODUCT_NAME_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRODUCT_PRICE_MAX_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRODUCT_PRICE_MIN_UNITS;

import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.entity.SalonProduct.Details;
import com.salonplatform.catalogue.service.ProductService.ProductInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.ArrayList;
import java.util.List;

/**
 * A product as a request sends it, checked against F05-S03 AC-8 and the prototype's {@code peErr}: every broken rule
 * is a field error naming it, and nothing is saved. The name is trimmed. The price is a whole number of units of the
 * salon's currency, the rupee limits scaled to minor units. Both settings must be sent: the API has no default for
 * them (the sheet starts both on). Whether the name is another product's is checked in the transaction.
 */
final class ProductValidation {

    private ProductValidation() {}

    static Details valid(ProductInput input, MinorUnits units) {
        if (input == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "is required")));
        }
        List<FieldProblem> errors = new ArrayList<>();
        String name = input.name() == null ? "" : input.name().strip();
        int length = name.codePointCount(0, name.length());
        if (length < PRODUCT_NAME_MIN || length > PRODUCT_NAME_MAX) {
            errors.add(new FieldProblem("name", "must be 2 to 40 characters"));
        }
        Long price = input.priceMinor();
        if (price == null
                || !units.whole(price)
                || price < units.minor(PRODUCT_PRICE_MIN_UNITS)
                || price > units.minor(PRODUCT_PRICE_MAX_UNITS)) {
            errors.add(new FieldProblem(
                    "priceMinor",
                    "must be a whole amount from " + PRODUCT_PRICE_MIN_UNITS + " to " + PRODUCT_PRICE_MAX_UNITS + " "
                            + units.currency()));
        }
        if (input.chargesVat() == null) {
            errors.add(new FieldProblem("chargesVat", "is required"));
        }
        if (input.soldAtCheckout() == null) {
            errors.add(new FieldProblem("soldAtCheckout", "is required"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.copyOf(errors));
        }
        return new Details(name, price, units.currency(), input.chargesVat(), input.soldAtCheckout());
    }
}
