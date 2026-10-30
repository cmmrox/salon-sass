package com.salonplatform.shared.secrets;

import java.util.Map;

/**
 * A bean that holds secret keys. The application refuses to start when any two keys held by any such beans
 * are the same, whichever modules hold them: a key used for two purposes lets a value made for one be taken
 * for the other (a signature, a ciphertext, an assertion).
 */
public interface SecretKeys {

    /** The keys held, each a copy named for what it is for ({@code identity signing}); none when not configured. */
    Map<String, byte[]> byPurpose();
}
