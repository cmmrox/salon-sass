package com.salonplatform.branding.service;

import java.util.UUID;

/**
 * A brand image as a reader is told of it: its id, kind and the size it was kept at.
 *
 * @param id the image, served at {@code /api/v1/salon/brand/images/{id}}
 * @param kind {@code logo} or {@code cover}
 * @param width its width in pixels
 * @param height its height in pixels
 */
public record BrandImageView(UUID id, String kind, int width, int height) {}
