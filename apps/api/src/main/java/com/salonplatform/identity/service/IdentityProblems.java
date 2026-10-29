package com.salonplatform.identity.service;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;

/** The answers identity's use cases refuse with. */
final class IdentityProblems {

    private IdentityProblems() {}

    static ApplicationProblemException invalidCredentials() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CREDENTIALS);
    }

    static ApplicationProblemException conflict() {
        return new ApplicationProblemException(409, ErrorCode.CONFLICT);
    }

    static ApplicationProblemException permissionDenied() {
        return new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
    }

    static ApplicationProblemException invalidField(String field, String detail) {
        return new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, detail)));
    }
}
