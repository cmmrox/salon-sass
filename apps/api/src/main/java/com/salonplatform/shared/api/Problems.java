package com.salonplatform.shared.api;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds the one shape every error response has: problem details with a stable {@code code}. */
final class Problems {

    static final String CODE = "code";

    private Problems() {
    }

    /** The answer for an error known only by its status. */
    static ProblemDetail of(HttpStatusCode status) {
        return of(status, ErrorCode.forStatus(status));
    }

    /** The answer for a particular outcome, which has a code of its own. */
    static ProblemDetail of(HttpStatusCode status, ErrorCode errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, errorCode.detail());
        problem.setTitle(ErrorCode.titleOf(status));
        problem.setProperty(CODE, errorCode.code());
        return problem;
    }
}
