package com.salonplatform.branding.service;

import java.util.Optional;

/**
 * A brand as a reader is given it.
 *
 * @param name the salon's display name
 * @param tagline its tagline, or empty
 * @param colour its brand colour, {@code #RRGGBB}
 * @param logo its logo image, or empty
 * @param cover its cover image, or empty
 */
public record BrandView(
        String name,
        Optional<String> tagline,
        String colour,
        Optional<BrandImageView> logo,
        Optional<BrandImageView> cover) {}
