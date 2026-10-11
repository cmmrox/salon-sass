package com.salonplatform.shared.api;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the one shape every error response has: problem details with a stable {@code code}. */
final class Problems {

    static final String CODE = "code";

    private Problems() {
    }

    static ProblemDetail of(HttpStatusCode status) {
        ErrorCode errorCode = ErrorCode.forStatus(status);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, errorCode.detail());
        problem.setTitle(ErrorCode.titleOf(status));
        problem.setProperty(CODE, errorCode.code());
        return problem;
    }
}
