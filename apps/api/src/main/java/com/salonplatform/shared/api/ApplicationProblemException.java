package com.salonplatform.shared.api;

import java.util.List;

/** A safe declared application outcome. Messages never include submitted values. */
public final class ApplicationProblemException extends RuntimeException {
    private final int status;
    private final ErrorCode code;
    private final List<FieldProblem> errors;
    private Long retryAfterSeconds;

    public ApplicationProblemException(int status, ErrorCode code) {
        this(status, code, List.of());
    }

    public ApplicationProblemException(int status, ErrorCode code, List<FieldProblem> errors) {
        super(code.code());
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
    }

    public ApplicationProblemException retryAfter(long seconds) {
        if (seconds < 1) throw new IllegalArgumentException("Retry time must be positive");
        this.retryAfterSeconds = seconds;
        return this;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldProblem> errors() {
        return errors;
    }
}
