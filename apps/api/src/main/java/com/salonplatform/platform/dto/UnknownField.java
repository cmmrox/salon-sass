package com.salonplatform.platform.dto;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;

/** A request field the console does not know is refused, naming the field, rather than ignored. */
final class UnknownField {

    private UnknownField() {}

    static ApplicationProblemException refused(String field) {
        return new ApplicationProblemException(
                400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, "is not allowed")));
    }
}
