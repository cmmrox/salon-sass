package com.salonplatform.platform.api;

import java.util.List;
import java.util.UUID;

import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.FieldProblem;
import com.salonplatform.shared.api.NotFoundException;

/** Shared edge rules of the console's controllers. */
final class OperatorRequests {

    private OperatorRequests() {
    }

    /** An unknown request field: 400 naming it. */
    static ApplicationProblemException unexpected(String field) {
        return new ApplicationProblemException(400, ErrorCode.BAD_REQUEST,
                List.of(new FieldProblem(field, "is not allowed")));
    }

    /** A path id: a UUID, or 400 naming {@code id}. */
    static UUID id(String value) {
        try {
            if (value != null && value.length() == 36) {
                return UUID.fromString(value);
            }
        }
        catch (IllegalArgumentException ex) {
            // Answered below.
        }
        throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem("id", "must be a UUID")));
    }

    static NotFoundException notFound() {
        return new NotFoundException();
    }
}
