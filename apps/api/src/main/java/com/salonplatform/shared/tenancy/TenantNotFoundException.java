package com.salonplatform.shared.tenancy;

/**
 * A tenant endpoint was called on a hostname that resolves to no salon. Answered 404
 * {@code not_found}, exactly as for a record that does not exist, so that a caller cannot probe
 * for salons.
 */
public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException() {
        super("The request's hostname resolves to no salon", null, false, false);
    }
}
