package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.domain.CatalogueLimits.BUFFER_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DEPOSIT_AMOUNT_MAX_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DEPOSIT_AMOUNT_MIN_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DEPOSIT_PERCENT_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DEPOSIT_PERCENT_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DESCRIPTION_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DURATION_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.DURATION_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.MINUTE_STEP;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRICE_MAX_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.PRICE_MIN_UNITS;
import static com.salonplatform.catalogue.domain.CatalogueLimits.SERVICE_NAME_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.SERVICE_NAME_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.VARIANTS_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.VARIANTS_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.VARIANT_NAME_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.VARIANT_SET_NAME_MAX;
import static com.salonplatform.catalogue.domain.CatalogueLimits.VARIANT_SET_NAME_MIN;

import com.salonplatform.catalogue.ServiceDeposit;
import com.salonplatform.catalogue.domain.MinorUnits;
import com.salonplatform.catalogue.service.SalonServices.DepositInput;
import com.salonplatform.catalogue.service.SalonServices.ServiceInput;
import com.salonplatform.catalogue.service.SalonServices.VariantInput;
import com.salonplatform.catalogue.service.SalonServices.VariantsInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A service as a request sends it, checked against the prototype's limits ({@code seErr}, F05-S01 AC-3, F05-S02 AC-1
 * and AC-7): every broken rule is a field error naming it, and nothing is saved. The name, description, set name and
 * variant names are trimmed; an empty description is none. Prices are whole units of the salon's currency, the
 * rupee limits scaled to minor units. Whether the category is the salon's, and whether a variant's id is one of the
 * current version's, are checked in the transaction.
 */
final class ServiceValidation {

    /**
     * The checked values.
     *
     * @param priceMinor null with variants
     * @param durationMinutes null with variants
     * @param variants null without variants
     */
    record Valid(
            String name,
            UUID categoryId,
            String description,
            Long priceMinor,
            Integer durationMinutes,
            int bufferMinutes,
            boolean bookableOnline,
            ValidVariants variants,
            ServiceDeposit deposit) {}

    /** A checked set of variants, in order. */
    record ValidVariants(String name, List<ValidVariant> items) {

        ValidVariants {
            items = List.copyOf(items);
        }
    }

    /**
     * A checked variant.
     *
     * @param id null for a new one
     * @param position its place in the request's list, for the field a refusal names
     */
    record ValidVariant(UUID id, int position, String name, long priceMinor, int durationMinutes) {}

    private static final String VARIANTS = "variants";

    private final List<FieldProblem> errors = new ArrayList<>();
    private final MinorUnits units;

    private ServiceValidation(MinorUnits units) {
        this.units = units;
    }

    /**
     * @param onlineRequired whether {@code bookableOnline} must be sent (a save), or is on when absent (a creation)
     */
    static Valid valid(ServiceInput input, MinorUnits units, boolean onlineRequired) {
        if (input == null) {
            throw new ApplicationProblemException(
                    400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("name", "is required")));
        }
        return new ServiceValidation(units).check(input, onlineRequired);
    }

    /** The field a refusal of a variant's id names. */
    static String variantField(int position, String field) {
        return VARIANTS + ".items[" + position + "]." + field;
    }

    private Valid check(ServiceInput input, boolean onlineRequired) {
        String name = input.name() == null ? "" : input.name().strip();
        if (outside(length(name), SERVICE_NAME_MIN, SERVICE_NAME_MAX)) {
            errors.add(new FieldProblem("name", "must be 2 to 40 characters"));
        }
        if (input.categoryId() == null) {
            errors.add(new FieldProblem("categoryId", "is required"));
        }
        String description =
                input.description() == null ? "" : input.description().strip();
        if (length(description) > DESCRIPTION_MAX) {
            errors.add(new FieldProblem("description", "must be at most 90 characters"));
        }
        ValidVariants variants = input.variants() == null ? null : variants(input.variants());
        Long price = input.priceMinor();
        Integer duration = input.durationMinutes();
        if (variants == null && input.variants() == null) {
            price = price("priceMinor", price);
            duration = duration("durationMinutes", duration);
        } else {
            absent("priceMinor", price);
            absent("durationMinutes", duration);
            price = null;
            duration = null;
        }
        Integer buffer = input.bufferMinutes();
        if (buffer == null || buffer < 0 || buffer > BUFFER_MAX || buffer % MINUTE_STEP != 0) {
            errors.add(new FieldProblem("bufferMinutes", "must be 0 to 60 minutes in steps of 5"));
        }
        if (onlineRequired && input.bookableOnline() == null) {
            errors.add(new FieldProblem("bookableOnline", "is required"));
        }
        Long lowest = price != null
                ? price
                : variants == null
                        ? null
                        : variants.items().stream()
                                .mapToLong(ValidVariant::priceMinor)
                                .min()
                                .orElseThrow();
        ServiceDeposit deposit = deposit(input.deposit(), lowest);
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.copyOf(errors));
        }
        return new Valid(
                name,
                input.categoryId(),
                description.isEmpty() ? null : description,
                price,
                duration,
                buffer,
                input.bookableOnline() == null || input.bookableOnline(),
                variants,
                deposit);
    }

    /** The set, or null when any of it is broken (each broken rule recorded). */
    private ValidVariants variants(VariantsInput input) {
        int before = errors.size();
        String setName = input.name() == null ? "" : input.name().strip();
        if (outside(length(setName), VARIANT_SET_NAME_MIN, VARIANT_SET_NAME_MAX)) {
            errors.add(new FieldProblem(VARIANTS + ".name", "must be 2 to 30 characters"));
        }
        List<VariantInput> items = input.items();
        if (items == null || items.size() < VARIANTS_MIN || items.size() > VARIANTS_MAX) {
            errors.add(new FieldProblem(VARIANTS + ".items", "must have 2 to 10 variants"));
            return null;
        }
        List<ValidVariant> valid = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            VariantInput item = items.get(i);
            if (item == null) {
                errors.add(new FieldProblem(VARIANTS + ".items[" + i + "]", "is required"));
                continue;
            }
            String variantName = item.name() == null ? "" : item.name().strip();
            if (outside(length(variantName), 1, VARIANT_NAME_MAX)) {
                errors.add(new FieldProblem(variantField(i, "name"), "must be 1 to 30 characters"));
            } else if (!names.add(variantName.toLowerCase(Locale.ROOT))) {
                errors.add(new FieldProblem(variantField(i, "name"), "is used by another variant in the set"));
            }
            if (item.id() != null && !ids.add(item.id())) {
                errors.add(new FieldProblem(variantField(i, "id"), "is given to another variant in the set"));
            }
            Long price = price(variantField(i, "priceMinor"), item.priceMinor());
            Integer duration = duration(variantField(i, "durationMinutes"), item.durationMinutes());
            if (price != null && duration != null) {
                valid.add(new ValidVariant(item.id(), i, variantName, price, duration));
            }
        }
        return errors.size() == before ? new ValidVariants(setName, valid) : null;
    }

    /**
     * The deposit; the salon's rule when none is sent.
     *
     * @param lowestPrice the service's price, or its variants' lowest; null when that is itself broken
     */
    private ServiceDeposit deposit(DepositInput input, Long lowestPrice) {
        String at = "deposit";
        if (input == null) {
            return new ServiceDeposit.SalonRule();
        }
        if (input.kind() == null) {
            errors.add(new FieldProblem(at + ".kind", "is required: salon_rule, none, percentage or amount"));
            return null;
        }
        switch (input.kind()) {
            case "salon_rule", "none" -> {
                absent(at + ".percent", input.percent());
                absent(at + ".amountMinor", input.amountMinor());
                return "none".equals(input.kind()) ? new ServiceDeposit.None() : new ServiceDeposit.SalonRule();
            }
            case "percentage" -> {
                absent(at + ".amountMinor", input.amountMinor());
                Integer percent = input.percent();
                if (percent == null || percent < DEPOSIT_PERCENT_MIN || percent > DEPOSIT_PERCENT_MAX) {
                    errors.add(new FieldProblem(at + ".percent", "must be a whole percentage from 1 to 100"));
                    return null;
                }
                return new ServiceDeposit.Percentage(percent);
            }
            case "amount" -> {
                absent(at + ".percent", input.percent());
                Long amount = input.amountMinor();
                long least = units.minor(DEPOSIT_AMOUNT_MIN_UNITS);
                long most = units.minor(DEPOSIT_AMOUNT_MAX_UNITS);
                if (amount == null || !units.whole(amount) || amount < least || amount > most) {
                    errors.add(new FieldProblem(
                            at + ".amountMinor",
                            "must be a whole amount from " + DEPOSIT_AMOUNT_MIN_UNITS + " to "
                                    + DEPOSIT_AMOUNT_MAX_UNITS + " " + units.currency()));
                    return null;
                }
                if (lowestPrice != null && amount > lowestPrice) {
                    errors.add(
                            new FieldProblem(at + ".amountMinor", "must not be more than the service's lowest price"));
                    return null;
                }
                return new ServiceDeposit.Amount(amount);
            }
            default -> {
                errors.add(new FieldProblem(at + ".kind", "must be salon_rule, none, percentage or amount"));
                return null;
            }
        }
    }

    private Long price(String field, Long price) {
        if (price == null
                || !units.whole(price)
                || price < units.minor(PRICE_MIN_UNITS)
                || price > units.minor(PRICE_MAX_UNITS)) {
            errors.add(new FieldProblem(
                    field,
                    "must be a whole amount from " + PRICE_MIN_UNITS + " to " + PRICE_MAX_UNITS + " "
                            + units.currency()));
            return null;
        }
        return price;
    }

    private Integer duration(String field, Integer duration) {
        if (duration == null || duration < DURATION_MIN || duration > DURATION_MAX || duration % MINUTE_STEP != 0) {
            errors.add(new FieldProblem(field, "must be 5 to 480 minutes in steps of 5"));
            return null;
        }
        return duration;
    }

    private void absent(String field, Object value) {
        if (value != null) {
            errors.add(new FieldProblem(field, "is not allowed here"));
        }
    }

    private static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean outside(int value, int least, int most) {
        return value < least || value > most;
    }
}
