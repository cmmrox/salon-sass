package com.salonplatform.identity.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The staff sign-in's limit per address across every salon ({@code salon.identity.sign-in.address-limit-across-salons},
 * decision 120): attempts in the same fifteen-minute window as the salon's own limits, 300 by default. It is not
 * tighter because many salons' staff share one carrier-grade NAT address on mobile data and every attempt counts;
 * it only stops one address spending the salon's own thirty attempts at every salon.
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
