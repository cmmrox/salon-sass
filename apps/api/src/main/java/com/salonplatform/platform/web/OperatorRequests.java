package com.salonplatform.platform.web;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;
import java.util.UUID;

/** Shared edge rules of the console's controllers. */
final class OperatorRequests {

    private OperatorRequests() {}

    /** An unknown request field: 400 naming it. */
    static ApplicationProblemException unexpected(String field) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, "is not allowed")));
    }

    /** A path id: a UUID, or 400 naming {@code id}. */
    static UUID id(String value) {
        try {
            if (value != null && value.length() == 36) {
                return UUID.fromString(value);
            }
        } catch (IllegalArgumentException ex) {
            // Answered below.
        }
        throw new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("id", "must be a UUID")));
    }
}
