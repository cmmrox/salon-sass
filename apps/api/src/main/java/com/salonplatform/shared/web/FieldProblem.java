package com.salonplatform.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One field of a request that failed validation: the field's name (a dotted path for a nested
 * field) and what is wrong with it. Never the value that was refused.
 *
 * @param field the field, as the request names it
 * @param detail what is wrong with it, safe to show
 * @param line for a file read line by line (a holiday calendar CSV, F04-S03), the line that failed,
 *     counted from 1; null, and absent from the answer, otherwise
 */
public record FieldProblem(
        String field,
        String detail,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer line) {

    /** A field that failed, with no line. */
    public FieldProblem(String field, String detail) {
        this(field, detail, null);
    }
}
