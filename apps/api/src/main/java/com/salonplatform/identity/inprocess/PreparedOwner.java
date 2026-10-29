package com.salonplatform.identity.inprocess;

/**
 * A salon's first Owner whose input passed identity's rules, with the password already hashed inside
 * identity. Only {@link IdentityProvisioning#prepareOwner} produces one, and
 * {@link IdentityProvisioning#bootstrapOwner} accepts no other, so no caller can bootstrap an Owner
 * with a password identity has not checked. The hash never leaves identity: this contract exposes the
 * name and email only, and the text form of identity's implementation shows neither.
 */
public interface PreparedOwner {

    /** The Owner's name, trimmed. */
    String name();

    /** The Owner's email, normalized. */
    String email();
}
