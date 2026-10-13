package com.salonplatform.shared.api;

import java.util.List;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the one shape every error response has: problem details with a stable {@code code} from
 * {@link ErrorCode}, the request's {@code traceId}, and for a request that failed validation the
 * {@code errors} naming each field.
 */
final class Problems {

    static final String CODE = "code";

    static final String TRACE_ID = "traceId";

    static final String ERRORS = "errors";

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

    /** A request whose body failed validation: 400 {@code bad_request}, with each failing field. */
    static ProblemDetail invalid(HttpStatusCode status, List<FieldProblem> errors) {
        ProblemDetail problem = of(status);
        problem.setProperty(ERRORS, List.copyOf(errors));
        return problem;
    }

    static ProblemDetail traced(ProblemDetail problem, String traceId) {
        problem.setProperty(TRACE_ID, traceId);
        return problem;
    }
}
