package com.salonplatform.platform.infrastructure;

/** Test access to the running context's operator proxy key, to sign rate-address assertions as the BFF does. */
public final class OperatorKeys {

    private OperatorKeys() {
    }

    public static byte[] proxyKey(OperatorCrypto crypto) {
        return crypto.proxyKey();
    }
}
