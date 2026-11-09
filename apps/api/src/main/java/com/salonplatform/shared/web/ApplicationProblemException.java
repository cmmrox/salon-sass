package com.salonplatform.shared.web;

import java.io.Serial;
import java.util.List;

/** A safe declared application outcome. Messages never include submitted values. */
public final class ApplicationProblemException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final ErrorCode code;
    private final transient List<FieldProblem> errors;
    private Long retryAfterSeconds;
    private String detail;

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

    /**
     * A sentence that says more than the code's own detail, such as which booking rule refused a change. It is
     * written by the application, never taken from the request or another exception.
     */
    public ApplicationProblemException withDetail(String sentence) {
        if (sentence == null || sentence.isBlank()) throw new IllegalArgumentException("A detail is a sentence");
        this.detail = sentence;
        return this;
    }

    /** The detail the body carries: {@link #withDetail}'s, else the code's own. */
    public String detail() {
        return detail == null ? code.detail() : detail;
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
