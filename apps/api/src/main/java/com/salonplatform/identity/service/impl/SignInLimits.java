package com.salonplatform.identity.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The staff sign-in's limit per address across every salon ({@code salon.identity.sign-in.address-limit-across-salons},
 * decision 120): failed attempts in the same fifteen-minute window as the salon's own limits, 60 by default. It
 * counts failures only, so that staff of many salons sharing one carrier-grade NAT address, who sign in
 * successfully, do not use it up, while password spraying across salons does.
 */
@Component
class SignInLimits {

    private final int addressAcrossSalons;

    SignInLimits(@Value("${salon.identity.sign-in.address-limit-across-salons}") int addressAcrossSalons) {
        if (addressAcrossSalons < 1) {
            throw new IllegalArgumentException("salon.identity.sign-in.address-limit-across-salons must be positive");
        }
        this.addressAcrossSalons = addressAcrossSalons;
    }

    /** The attempts one address may make across every salon in a window. */
    int addressAcrossSalons() {
        return addressAcrossSalons;
    }
}
