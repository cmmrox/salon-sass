package com.salonplatform.shared.images;

/**
 * What an uploaded image must be and how it is kept.
 *
 * @param noun how a refusal names the image, as in "a cover is kept at most 1600 pixels high"
 * @param minWidth the narrowest image accepted, as uploaded
 * @param minHeight the shortest image accepted, as uploaded
 * @param maxWidth the widest it is kept; a wider one is scaled down to it
 * @param maxHeight the tallest it is kept: an image that would be kept taller is refused from its header
 * @param squareCrop whether it is cropped to its centre square before it is scaled
 * @param format how it is kept
 */
public record ImageShape(
        String noun, int minWidth, int minHeight, int maxWidth, int maxHeight, boolean squareCrop, ImageFormat format) {

    public ImageShape {
        if (minWidth < 1 || minHeight < 1 || maxWidth < 1 || maxHeight < 1 || noun == null || format == null) {
            throw new IllegalArgumentException("an image shape has positive sizes, a noun and a format");
        }
    }
}
