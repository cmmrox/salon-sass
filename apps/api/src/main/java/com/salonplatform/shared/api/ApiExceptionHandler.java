package com.salonplatform.shared.api;

import com.salonplatform.shared.tenancy.TenantNotFoundException;
import com.salonplatform.shared.tenancy.TenantSuspendedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Turns every exception that reaches the web layer into problem details with a stable {@code code}
 * and the request's {@code traceId}. The body is built from the status, or from the code of the
 * particular outcome a handler here names, and for a request body that failed validation it names
 * each failing field: nothing from the exception (its message, its class, a stack trace, the value
 * it refused) is ever written to the response.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        // Only a body built here, by Problems, is kept. Anything the framework made is replaced.
        ProblemDetail problem = body instanceof CodedProblem coded ? coded.problem() : Problems.of(statusCode);
        Problems.traced(problem, traceIdOf(request));
        return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
    }

    /** A body one of the handlers below built for an outcome with a code of its own. */
    private record CodedProblem(ProblemDetail problem) {}

    /**
     * A request body whose fields broke their constraints: each field, and what is wrong with it.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // A constraint's message describes the rule, never the value. A value that could not
            // even be bound has only a message that may quote it, so it gets a sentence of ours.
            String detail = error.isBindingFailure() || error.getDefaultMessage() == null
                    ? "has the wrong type"
                    : error.getDefaultMessage();
            errors.add(new FieldProblem(error.getField(), detail));
        }
        errors.sort(java.util.Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::detail));
        return handleExceptionInternal(
                ex, new CodedProblem(Problems.invalid(status, errors)), headers, status, request);
    }

    /**
     * A request body that is not JSON, or whose JSON does not fit: the field whose value has the
     * wrong type when there is one, and an empty list when the body as a whole could not be read.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldProblem> errors = new ArrayList<>();
        // A strict request's any-setter reports its unknown field before construction. Jackson
        // wraps that declared edge validation, at most with the path of the enclosing object; the
        // declared field is the more precise one (F03-S01: `owner.role`, not `owner`).
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ApplicationProblemException declared
                    && declared.status() == 400
                    && declared.code() == ErrorCode.BAD_REQUEST) {
                errors.addAll(declared.errors());
                break;
            }
        }
        if (errors.isEmpty() && ex.getCause() instanceof JacksonException json) {
            String field = fieldOf(json);
            if (field != null) {
                errors.add(new FieldProblem(field, describe(json)));
            }
        }
        return handleExceptionInternal(
                ex, new CodedProblem(Problems.invalid(status, errors)), headers, status, request);
    }

    /** The hostname names no salon: the same answer as for anything else that does not exist. */
    @ExceptionHandler(TenantNotFoundException.class)
    @Nullable
    ResponseEntity<Object> handleNoSalon(TenantNotFoundException ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    /**
     * The hostname names a salon that is suspended. The body says so and carries nothing of the
     * salon: no name, no colour, no id.
     */
    @ExceptionHandler(TenantSuspendedException.class)
    @Nullable
    ResponseEntity<Object> handleSuspendedSalon(TenantSuspendedException ex, WebRequest request) {
        CodedProblem body = new CodedProblem(Problems.of(HttpStatus.FORBIDDEN, ErrorCode.SALON_SUSPENDED));
        return handleExceptionInternal(ex, body, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
    }

    /** Something that names nothing that exists, such as a record of another salon: 404. */
    @ExceptionHandler(NotFoundException.class)
    @Nullable
    ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(ApplicationProblemException.class)
    @Nullable
    ResponseEntity<Object> handleApplicationProblem(ApplicationProblemException ex, WebRequest request) {
        ProblemDetail problem = ex.errors().isEmpty()
                ? Problems.of(HttpStatusCode.valueOf(ex.status()), ex.code())
                : Problems.invalid(HttpStatusCode.valueOf(ex.status()), ex.code(), ex.errors());
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        if (ex.status() == 429)
            headers.set("Retry-After", ex.retryAfterSeconds() == null ? "900" : Long.toString(ex.retryAfterSeconds()));
        return handleExceptionInternal(
                ex, new CodedProblem(problem), headers, HttpStatusCode.valueOf(ex.status()), request);
    }

    @ExceptionHandler(Exception.class)
    @Nullable
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        if (DatabaseUnavailable.isCauseOf(ex)) {
            // Stopped, unreachable or silent: the request could not be served, and may be tried
            // again.
            log.warn("The database is unavailable: {}", DatabaseUnavailable.describe(ex));
            return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.SERVICE_UNAVAILABLE, request);
        }
        log.error("Unhandled exception", ex);
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    private static String traceIdOf(WebRequest request) {
        if (request instanceof NativeWebRequest nativeRequest
                && nativeRequest.getNativeRequest(HttpServletRequest.class) instanceof HttpServletRequest http) {
            return RequestTrace.of(http);
        }
        return RequestTrace.newTraceId();
    }

    /** The JSON path of the value that could not be read, {@code a.b[0].c}, or null for none. */
    private static @Nullable String fieldOf(JacksonException json) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : json.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    private static String describe(JacksonException json) {
        if (json instanceof MismatchedInputException mismatch && mismatch.getTargetType() != null) {
            Class<?> type = mismatch.getTargetType();
            if (type == Integer.class
                    || type == int.class
                    || type == Long.class
                    || type == long.class
                    || type == Short.class
                    || type == short.class) {
                return "must be a whole number";
            }
            if (type == Boolean.class || type == boolean.class) {
                return "must be true or false";
            }
            if (type == String.class) {
                return "must be text";
            }
        }
        return "has the wrong type";
    }
}
