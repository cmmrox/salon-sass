package com.salonplatform.shared.api;

/**
 * One field of a request that failed validation: the field's name (a dotted path for a nested
 * field) and what is wrong with it. Never the value that was refused.
 *
 * @param field the field, as the request names it
 * @param detail what is wrong with it, safe to show
 */
public record FieldProblem(String field, String detail) {}
