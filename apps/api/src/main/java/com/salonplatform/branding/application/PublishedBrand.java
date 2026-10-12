package com.salonplatform.branding.application;

/**
 * What customers see of a salon's brand.
 *
 * @param slug the salon's slug
 * @param name its display name
 * @param brandColour its brand colour, {@code #RRGGBB}
 */
public record PublishedBrand(String slug, String name, String brandColour) {
}
