package com.salonplatform.identity;

/**
 * Who the current request acts as, for a module that checks a permission before it acts. It needs no
 * transaction from its caller: it reads the request's session in a tenant transaction it opens itself.
 */
public interface Principals {

    /**
     * The principal of the current request.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException when the request carries no valid
     *     session for the hostname's salon
     */
    Principal current();

    /**
     * The principal of the current request, when it holds the permission.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied} when it
     *     does not, and as {@link #current()} when there is no valid session
     */
    Principal require(String permission);
}
