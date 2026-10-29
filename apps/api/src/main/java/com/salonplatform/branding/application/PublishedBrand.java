package com.salonplatform.branding.application;

/**
 * What customers see of a salon's brand.
 *
 * @param slug the salon's slug
 * @param name its display name
 * @param brandColour its brand colour, {@code #RRGGBB}
 * @param timeZone the salon's time zone, an IANA name
 */
public record PublishedBrand(String slug, String name, String brandColour, String timeZone) {}
