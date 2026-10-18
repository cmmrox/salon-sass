package com.salonplatform.shared.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * The stable, machine-readable reason carried by every error response as {@code code}. Clients and
 * tests branch on it, never on the wording. A code, once published, is not renamed.
 *
 * <p>This is the one place codes are listed: the OpenAPI document's {@code Problem.code} is
 * generated from it. The general codes are chosen by status ({@link #forStatus}); a code for a
 * particular outcome, such as {@link #SALON_SUSPENDED}, is named by the handler that answers it.
 */
public enum ErrorCode {

    INVALID_CLIENT_ASSERTION("invalid_client_assertion", "The client assertion could not be verified."),
    CONFIRMATION_REQUIRED("confirmation_required", "Confirm account deletion to continue."),
    INVALID_CODE("invalid_code", "The verification code could not be verified."),
    CODE_EXPIRED("code_expired", "Please request a new verification code."),
    PROVIDER_UNAVAILABLE("provider_unavailable", "Code delivery is not configured."),
    INVALID_CREDENTIALS("invalid_credentials", "The sign-in credentials could not be verified."),
    INVALID_SESSION("invalid_session", "Please sign in again."),
    PERMISSION_DENIED("permission_denied", "Your account does not have permission for this action."),
    CONFLICT("conflict", "The record changed or this change conflicts with an existing record."),
    RATE_LIMITED("rate_limited", "Please wait before trying again."),
    /** Another salon already has this slug (F03-S01). */
    SLUG_TAKEN("slug_taken", "Another salon already uses this web address."),
    /** Another plan already has this key (F03-S01). */
    PLAN_KEY_TAKEN("plan_key_taken", "Another plan already uses this key."),
    /** The idempotency key was already used with a different request (F03-S01). */
    IDEMPOTENCY_CONFLICT("idempotency_conflict", "This request key was already used for a different request."),
    BAD_REQUEST("bad_request", "The request could not be understood."),
    NOT_FOUND("not_found", "The requested resource was not found."),
    METHOD_NOT_ALLOWED("method_not_allowed", "The method is not allowed for this resource."),
    NOT_ACCEPTABLE("not_acceptable", "The resource cannot be returned in the requested format."),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", "The format of the request body is not supported."),
    REQUEST_REJECTED("request_rejected", "The request was not accepted."),
    SERVICE_UNAVAILABLE("service_unavailable", "The service is temporarily unavailable."),
    /** The hostname's salon is suspended: 403, on every endpoint that needs a salon. */
    SALON_SUSPENDED("salon_suspended", "This salon is not available at the moment."),
    INTERNAL_ERROR("internal_error", "Something went wrong on our side.");

    private final String code;
    private final String detail;

    ErrorCode(String code, String detail) {
        this.code = code;
        this.detail = detail;
    }

    public String code() {
        return code;
    }

    /** A sentence that is safe to show: it never carries anything taken from an exception. */
    public String detail() {
        return detail;
    }

    /** The code for an error the framework or the server raised, known only by its status. */
    public static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> BAD_REQUEST;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? REQUEST_REJECTED : INTERNAL_ERROR;
        };
    }

    static String titleOf(HttpStatusCode status) {
        HttpStatus known = HttpStatus.resolve(status.value());
        return known != null ? known.getReasonPhrase() : "Error";
    }
}
