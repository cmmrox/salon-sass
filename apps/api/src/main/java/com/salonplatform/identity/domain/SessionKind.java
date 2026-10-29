package com.salonplatform.identity.domain;

/** Whose a session is, as {@code identity_session.kind} holds it: a staff member's or a customer's. */
public final class SessionKind {

    public static final String STAFF = "staff";

    public static final String CUSTOMER = "customer";

    private SessionKind() {}
}
