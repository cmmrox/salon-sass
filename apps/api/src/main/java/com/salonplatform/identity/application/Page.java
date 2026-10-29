package com.salonplatform.identity.application;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;

public record Page<T>(List<T> items, int page, int size, long total) {

    /** Lists are paginated, page 0 to 100,000 and size 1 to 100 (docs/architecture/identity-and-access.md). */
    static void requireValid(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("page", "page must be 0 to 100000 and size 1 to 100")));
        }
    }
}
