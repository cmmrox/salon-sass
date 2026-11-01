package com.salonplatform.booking.service.impl;

import static com.salonplatform.booking.domain.RuleOptions.BOOKING_HORIZON_DAYS;
import static com.salonplatform.booking.domain.RuleOptions.FREE_CANCELLATION_HOURS;
import static com.salonplatform.booking.domain.RuleOptions.MAX_AMOUNT_UNITS;
import static com.salonplatform.booking.domain.RuleOptions.MAX_PERCENT;
import static com.salonplatform.booking.domain.RuleOptions.MAX_SAFE_MINOR;
import static com.salonplatform.booking.domain.RuleOptions.MINIMUM_NOTICE_MINUTES;
import static com.salonplatform.booking.domain.RuleOptions.MIN_AMOUNT_UNITS;
import static com.salonplatform.booking.domain.RuleOptions.MIN_CAP_UNITS;
import static com.salonplatform.booking.domain.RuleOptions.MIN_PERCENT;
import static com.salonplatform.booking.domain.RuleOptions.RESCHEDULE_LIMITS;
import static com.salonplatform.booking.domain.RuleOptions.RESCHEDULE_UNTIL_HOURS;
import static com.salonplatform.booking.domain.RuleOptions.SLOT_INTERVAL_MINUTES;

import com.salonplatform.booking.BookingTerms;
import com.salonplatform.booking.DepositRule;
import com.salonplatform.booking.Rescheduling;
import com.salonplatform.booking.Retention;
import com.salonplatform.booking.domain.MinorUnits;
import com.salonplatform.booking.service.BookingRulesService.DepositInput;
import com.salonplatform.booking.service.BookingRulesService.RescheduleInput;
import com.salonplatform.booking.service.BookingRulesService.RetentionInput;
import com.salonplatform.booking.service.BookingRulesService.RulesInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The rules a request sends, checked against the prototype's choices and limits (F04-S04 AC-2, AC-3): every
 * broken rule is a field error naming it ({@code rules.deposit.capMinor}), and nothing is saved. Amount limits
 * are whole units of the currency scaled to minor units; an amount must be whole units (whole rupees), because
 * the salon enters whole rupees.
 */
final class BookingRulesValidation {

    private static final String RULES = "rules";

    private final List<FieldProblem> errors = new ArrayList<>();
    private final MinorUnits units;

    private BookingRulesValidation(MinorUnits units) {
        this.units = units;
    }

    /** The rules, or 400 naming each field that broke a rule. */
    static BookingTerms valid(RulesInput input, MinorUnits units) {
        BookingRulesValidation check = new BookingRulesValidation(units);
        BookingTerms terms = check.terms(input);
        if (!check.errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, check.errors);
        }
        return terms;
    }

    private BookingTerms terms(RulesInput input) {
        if (input == null) {
            errors.add(new FieldProblem(RULES, "is required"));
            return null;
        }
        Integer interval = oneOf(RULES + ".slotIntervalMinutes", input.slotIntervalMinutes(), SLOT_INTERVAL_MINUTES);
        Integer notice = oneOf(RULES + ".minimumNoticeMinutes", input.minimumNoticeMinutes(), MINIMUM_NOTICE_MINUTES);
        Integer horizon = oneOf(RULES + ".bookingHorizonDays", input.bookingHorizonDays(), BOOKING_HORIZON_DAYS);
        DepositRule deposit = deposit(input.deposit());
        Integer free = oneOf(RULES + ".freeCancellationHours", input.freeCancellationHours(), FREE_CANCELLATION_HOURS);
        Retention late = retention(RULES + ".lateCancellation", input.lateCancellation(), true);
        Retention noShow = retention(RULES + ".noShow", input.noShow(), false);
        Rescheduling reschedule = reschedule(input.reschedule());
        if (!errors.isEmpty()) {
            return null;
        }
        return new BookingTerms(interval, notice, horizon, deposit, free, late, noShow, reschedule);
    }

    private DepositRule deposit(DepositInput input) {
        String at = RULES + ".deposit";
        if (input == null) {
            errors.add(new FieldProblem(at, "is required"));
            return null;
        }
        if (input.kind() == null) {
            errors.add(new FieldProblem(at + ".kind", "is required: none, percentage or amount"));
            return null;
        }
        switch (input.kind()) {
            case "none" -> {
                absent(at + ".percent", input.percent(), "with no deposit");
                absent(at + ".capMinor", input.capMinor(), "with no deposit");
                absent(at + ".amountMinor", input.amountMinor(), "with no deposit");
                return new DepositRule.None();
            }
            case "percentage" -> {
                Integer percent = percent(at + ".percent", input.percent());
                Long cap = cap(at + ".capMinor", input.capMinor());
                absent(at + ".amountMinor", input.amountMinor(), "with a percentage deposit");
                return percent == null || cap == null ? null : new DepositRule.Percentage(percent, cap);
            }
            case "amount" -> {
                Long amount = amount(at + ".amountMinor", input.amountMinor());
                absent(at + ".percent", input.percent(), "with a fixed amount");
                absent(at + ".capMinor", input.capMinor(), "with a fixed amount");
                return amount == null ? null : new DepositRule.Amount(amount);
            }
            default -> {
                errors.add(new FieldProblem(at + ".kind", "must be none, percentage or amount"));
                return null;
            }
        }
    }

    private Retention retention(String at, RetentionInput input, boolean nothingAllowed) {
        String kinds = nothingAllowed ? "nothing, deposit or share" : "deposit or share";
        if (input == null) {
            errors.add(new FieldProblem(at, "is required"));
            return null;
        }
        if (input.kind() == null) {
            errors.add(new FieldProblem(at + ".kind", "is required: " + kinds));
            return null;
        }
        switch (input.kind()) {
            case "nothing" -> {
                if (!nothingAllowed) {
                    errors.add(new FieldProblem(at + ".kind", "must be " + kinds));
                    return null;
                }
                absent(at + ".percent", input.percent(), "unless a share is kept");
                return new Retention.Nothing();
            }
            case "deposit" -> {
                absent(at + ".percent", input.percent(), "unless a share is kept");
                return new Retention.Deposit();
            }
            case "share" -> {
                Integer percent = percent(at + ".percent", input.percent());
                return percent == null ? null : new Retention.Share(percent);
            }
            default -> {
                errors.add(new FieldProblem(at + ".kind", "must be " + kinds));
                return null;
            }
        }
    }

    private Rescheduling reschedule(RescheduleInput input) {
        String at = RULES + ".reschedule";
        if (input == null) {
            errors.add(new FieldProblem(at, "is required"));
            return null;
        }
        Integer limit = input.limit();
        if (limit != null && !RESCHEDULE_LIMITS.contains(limit)) {
            errors.add(new FieldProblem(at + ".limit", "must be 0 (not online), 1, 2 or null (any number of times)"));
            return null;
        }
        if (limit != null && limit == 0) {
            absent(at + ".untilHours", input.untilHours(), "when rescheduling is not online");
            return new Rescheduling.NotOnline();
        }
        Integer until = oneOf(at + ".untilHours", input.untilHours(), RESCHEDULE_UNTIL_HOURS);
        if (until == null) {
            return null;
        }
        return limit == null ? new Rescheduling.Unlimited(until) : new Rescheduling.Limited(limit, until);
    }

    private Integer oneOf(String field, Integer value, List<Integer> allowed) {
        if (value == null || !allowed.contains(value)) {
            errors.add(new FieldProblem(field, "must be one of " + listed(allowed)));
            return null;
        }
        return value;
    }

    private Integer percent(String field, Integer value) {
        if (value == null || value < MIN_PERCENT || value > MAX_PERCENT) {
            errors.add(new FieldProblem(field, "must be a whole percentage from 1 to 100"));
            return null;
        }
        return value;
    }

    private Long cap(String field, Long value) {
        long least = units.minor(MIN_CAP_UNITS);
        if (value == null || value < least) {
            errors.add(new FieldProblem(
                    field, "must be at least " + least + " (" + MIN_CAP_UNITS + " " + currency() + ")"));
            return null;
        }
        if (value > MAX_SAFE_MINOR) {
            errors.add(new FieldProblem(field, "must be at most " + MAX_SAFE_MINOR));
            return null;
        }
        return whole(field, value);
    }

    private Long amount(String field, Long value) {
        long least = units.minor(MIN_AMOUNT_UNITS);
        long most = units.minor(MAX_AMOUNT_UNITS);
        if (value == null || value < least || value > most) {
            errors.add(new FieldProblem(
                    field,
                    "must be from " + least + " to " + most + " (" + MIN_AMOUNT_UNITS + " to " + MAX_AMOUNT_UNITS + " "
                            + currency() + ")"));
            return null;
        }
        return whole(field, value);
    }

    private Long whole(String field, long value) {
        if (!units.whole(value)) {
            errors.add(new FieldProblem(field, "must be a whole number of " + currency()));
            return null;
        }
        return value;
    }

    private void absent(String field, Object value, String when) {
        if (value != null) {
            errors.add(new FieldProblem(field, "is not allowed " + when));
        }
    }

    private String currency() {
        return units.currency();
    }

    private static String listed(List<Integer> allowed) {
        return allowed.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }
}
