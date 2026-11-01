package com.salonplatform.branding.service;

import java.util.Optional;

/**
 * What customers see of a salon's brand and business profile.
 *
 * @param slug the salon's slug
 * @param name its display name
 * @param brandColour its brand colour, {@code #RRGGBB}
 * @param timeZone the salon's time zone, an IANA name
 * @param profile its published business profile; empty before the first publication
 */
public record PublishedBrand(
        String slug, String name, String brandColour, String timeZone, Optional<ProfileView> profile) {}
