package com.salonplatform.shared.api;

import com.salonplatform.shared.tenancy.TenantNotFoundException;
import com.salonplatform.shared.tenancy.TenantSuspendedException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception that reaches the web layer into problem details with a stable
 * {@code code}. The body is built from the status, or from the code of the particular outcome a
 * handler here names: nothing from the exception (its message, its class, a stack trace) is ever
 * written to the response.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // Only a body built here, by Problems, is kept. Anything the framework made is replaced.
        ProblemDetail problem = body instanceof CodedProblem coded ? coded.problem() : Problems.of(statusCode);
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    /** A body one of the handlers below built for an outcome with a code of its own. */
    private record CodedProblem(ProblemDetail problem) {
    }

    /** The hostname names no salon: the same answer as for anything else that does not exist. */
    @ExceptionHandler(TenantNotFoundException.class)
    @Nullable ResponseEntity<Object> handleNoSalon(TenantNotFoundException ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    /**
     * The hostname names a salon that is suspended. The body says so and carries nothing of the
     * salon: no name, no colour, no id.
     */
    @ExceptionHandler(TenantSuspendedException.class)
    @Nullable ResponseEntity<Object> handleSuspendedSalon(TenantSuspendedException ex, WebRequest request) {
        CodedProblem body = new CodedProblem(Problems.of(HttpStatus.FORBIDDEN, ErrorCode.SALON_SUSPENDED));
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler(Exception.class)
    @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        if (DatabaseUnavailable.isCauseOf(ex)) {
            // Stopped, unreachable or silent: the request could not be served, and may be tried again.
            log.warn("The database is unavailable: {}", DatabaseUnavailable.describe(ex));
            return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
        }
        log.error("Unhandled exception", ex);
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }
}
