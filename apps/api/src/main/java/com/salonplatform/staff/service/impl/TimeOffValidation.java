package com.salonplatform.staff.service.impl;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.staff.domain.LeaveType;
import com.salonplatform.staff.domain.LocalTimes;
import com.salonplatform.staff.service.MemberTimeOffService.BlockInput;
import com.salonplatform.staff.service.MemberTimeOffService.LeaveInput;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Leave or a block as a request sends it, checked against F06-S03's AC-1 and AC-4: every broken rule is a field error, and
 * nothing is saved. The fields a broken rule names are those of the request. {@code today} is always the salon's.
 */
final class TimeOffValidation {

    /** A leave's note is up to 60 characters (AC-1). */
    static final int NOTE_MAX = 60;

    /** A block's label is 2 to 40 characters, trimmed (AC-4). */
    static final int LABEL_MIN = 2;

    static final int LABEL_MAX = 40;

    private static final String ON_A_QUARTER_HOUR = "must be a time on a quarter hour, HH:MM";

    /** Whole days, the last not before the first. */
    record Days(LocalDate firstDay, LocalDate lastDay) {}

    /** The checked leave. {@code note} is null for none. */
    record ValidLeave(Days days, LeaveType type, String note) {}

    /** Part of one date, the finish after the start. */
    record Part(LocalDate date, LocalTime starts, LocalTime finishes) {}

    /** The checked block. */
    record ValidBlock(Part part, String label) {}

    private TimeOffValidation() {}

    /** The days, or 400 naming each bad field. */
    static Days days(String firstDay, String lastDay, LocalDate today) {
        List<FieldProblem> errors = new ArrayList<>();
        Days days = days(firstDay, lastDay, today, errors);
        refuseAny(errors);
        return days;
    }

    /** The part of a date, or 400 naming each bad field. */
    static Part part(String date, String starts, String finishes, LocalDate today) {
        List<FieldProblem> errors = new ArrayList<>();
        Part part = part(date, starts, finishes, today, errors);
        refuseAny(errors);
        return part;
    }

    /** The leave, or 400 naming each bad field. */
    static ValidLeave leave(LeaveInput input, LocalDate today) {
        List<FieldProblem> errors = new ArrayList<>();
        Days days = days(input.firstDay(), input.lastDay(), today, errors);
        LeaveType type = LeaveType.of(input.type());
        if (type == null) {
            errors.add(new FieldProblem("type", "must be annual, sick, personal or other"));
        }
        String note = input.note() == null ? null : input.note().strip();
        if (note != null && note.isEmpty()) {
            note = null;
        }
        if (note != null && (lengthOf(note) > NOTE_MAX || hasControl(note))) {
            errors.add(new FieldProblem("note", "must be at most " + NOTE_MAX + " characters of plain text"));
        }
        refuseAny(errors);
        return new ValidLeave(days, type, note);
    }

    /** The block, or 400 naming each bad field. */
    static ValidBlock block(BlockInput input, LocalDate today) {
        List<FieldProblem> errors = new ArrayList<>();
        Part part = part(input.date(), input.starts(), input.finishes(), today, errors);
        String label = input.label() == null ? "" : input.label().strip();
        if (lengthOf(label) < LABEL_MIN || lengthOf(label) > LABEL_MAX || hasControl(label)) {
            errors.add(new FieldProblem(
                    "label", "must be " + LABEL_MIN + " to " + LABEL_MAX + " characters of plain text"));
        }
        refuseAny(errors);
        return new ValidBlock(part, label);
    }

    private static Days days(String firstDay, String lastDay, LocalDate today, List<FieldProblem> errors) {
        LocalDate first = date("firstDay", firstDay, today, errors);
        LocalDate last = date("lastDay", lastDay, today, errors);
        if (first != null && last != null && last.isBefore(first)) {
            errors.add(new FieldProblem("lastDay", "must not be before the first day"));
        }
        return new Days(first, last);
    }

    private static Part part(String date, String starts, String finishes, LocalDate today, List<FieldProblem> errors) {
        LocalDate on = date("date", date, today, errors);
        LocalTime from = LocalTimes.quarterHour(starts);
        LocalTime to = LocalTimes.quarterHour(finishes);
        if (from == null) {
            errors.add(new FieldProblem("starts", ON_A_QUARTER_HOUR));
        }
        if (to == null) {
            errors.add(new FieldProblem("finishes", ON_A_QUARTER_HOUR));
        } else if (from != null && !to.isAfter(from)) {
            errors.add(new FieldProblem("finishes", "must be after the start"));
        }
        return new Part(on, from, to);
    }

    /** A date from today to a year ahead, the rule a closure's date follows, or null with the problem named. */
    private static LocalDate date(String field, String text, LocalDate today, List<FieldProblem> errors) {
        try {
            return ClosureServiceImpl.closable(text, today);
        } catch (ApplicationProblemException ex) {
            ex.errors().forEach(problem -> errors.add(new FieldProblem(field, problem.detail())));
            return null;
        }
    }

    private static int lengthOf(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(Character::isISOControl);
    }

    private static void refuseAny(List<FieldProblem> errors) {
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
    }
}
