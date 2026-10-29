package com.salonplatform.platform.service;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.ArrayList;
import java.util.List;

/**
 * A list request: page 0 to 100,000, size 1 to 100 (default 20), and an optional literal search of up
 * to 120 characters, trimmed (blank is none). Each bad value is a field error.
 */
public record Paging(int page, int size, String q) {

    public static Paging of(String page, String size, String q) {
        List<FieldProblem> errors = new ArrayList<>();
        int p = number(page, 0, 0, 100000, "page", errors);
        int s = number(size, 20, 1, 100, "size", errors);
        String search = q == null ? null : q.strip();
        if (search != null
                && (search.codePointCount(0, search.length()) > 120
                        || search.codePoints().anyMatch(Character::isISOControl))) {
            errors.add(new FieldProblem("q", "must be plain text of at most 120 characters"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Paging(p, s, search == null || search.isEmpty() ? null : search);
    }

    private static int number(String value, int fallback, int min, int max, String field, List<FieldProblem> errors) {
        if (value == null) {
            return fallback;
        }
        if (!value.matches("0|[1-9][0-9]{0,6}") || Integer.parseInt(value) < min || Integer.parseInt(value) > max) {
            errors.add(new FieldProblem(field, "must be a whole number from " + min + " to " + max));
            return fallback;
        }
        return Integer.parseInt(value);
    }

    public int offset() {
        return page * size;
    }
}
