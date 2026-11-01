package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.domain.CatalogueLimits.BUFFER_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DESCRIPTION_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DURATION_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DURATION_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.MINUTE_STEP;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRICE_MAX_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRICE_MIN_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.SERVICE_NAME_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.SERVICE_NAME_MIN;

import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.service.SalonServices.ServiceInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A service as a request sends it, checked against the prototype's limits ({@code seErr}, F05-S01 AC-3): every
 * broken rule is a field error naming it, and nothing is saved. The name and description are trimmed; an empty
 * description is none. The price is whole units of the salon's currency, the rupee limits scaled to minor units.
 * Whether the category is the salon's is checked in the transaction.
 */
final class ServiceValidation {

    /** The checked values. */
    record Valid(
            String name,
            UUID categoryId,
            String description,
            long priceMinor,
            int durationMinutes,
            int bufferMinutes,
            boolean bookableOnline) {}

    private ServiceValidation() {}

    /**
     * @param onlineRequired whether {@code bookableOnline} must be sent (a save), or is on when absent (a creation)
     */
    static Valid valid(ServiceInput input, MinorUnits units, boolean onlineRequired) {
        List<FieldProblem> errors = new ArrayList<>();
        if (input == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "is required")));
        }
        String name = input.name() == null ? "" : input.name().strip();
        int nameLength = name.codePointCount(0, name.length());
        if (nameLength < SERVICE_NAME_MIN || nameLength > SERVICE_NAME_MAX) {
            errors.add(new FieldProblem("name", "must be 2 to 40 characters"));
        }
        if (input.categoryId() == null) {
            errors.add(new FieldProblem("categoryId", "is required"));
        }
        String description =
                input.description() == null ? "" : input.description().strip();
        if (description.codePointCount(0, description.length()) > DESCRIPTION_MAX) {
            errors.add(new FieldProblem("description", "must be at most 90 characters"));
        }
        Long price = input.priceMinor();
        if (price == null
                || !units.whole(price)
                || price < units.minor(PRICE_MIN_UNITS)
                || price > units.minor(PRICE_MAX_UNITS)) {
            errors.add(new FieldProblem(
                    "priceMinor",
                    "must be a whole amount from " + PRICE_MIN_UNITS + " to " + PRICE_MAX_UNITS + " "
                            + units.currency()));
        }
        Integer duration = input.durationMinutes();
        if (duration == null || duration < DURATION_MIN || duration > DURATION_MAX || duration % MINUTE_STEP != 0) {
            errors.add(new FieldProblem("durationMinutes", "must be 5 to 480 minutes in steps of 5"));
        }
        Integer buffer = input.bufferMinutes();
        if (buffer == null || buffer < 0 || buffer > BUFFER_MAX || buffer % MINUTE_STEP != 0) {
            errors.add(new FieldProblem("bufferMinutes", "must be 0 to 60 minutes in steps of 5"));
        }
        if (onlineRequired && input.bookableOnline() == null) {
            errors.add(new FieldProblem("bookableOnline", "is required"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Valid(
                name,
                input.categoryId(),
                description.isEmpty() ? null : description,
                price,
                duration,
                buffer,
                input.bookableOnline() == null || input.bookableOnline());
    }
}
