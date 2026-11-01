package com.salonplatform.branding.domain;

import com.salonplatform.shared.images.ImageCheck;
import com.salonplatform.shared.images.ImageFormat;
import com.salonplatform.shared.images.ImageShape;
import com.salonplatform.shared.images.UploadedImages;
import java.util.Locale;

/**
 * A brand image as uploaded, checked and remade (F04-S02, decision 086), through the shared image checks
 * ({@link UploadedImages}, decision 093): the content decides what it is, its header's pixels are limited before
 * it is decoded, and it is re-encoded so no metadata survives.
 *
 * <p>A logo is cropped to its centre square and kept as a PNG of at most 512 x 512, at least 128 x 128 as
 * uploaded. A cover is kept as a JPEG at most 1600 wide and 1600 high, at least 800 x 300 as uploaded; a cover
 * whose shape would keep it taller than 1600 is refused. However large or oddly shaped an image is, it is decoded
 * into at most {@link #DECODE_BUDGET} pixels.
 */
public final class BrandImages {

    /** The most pixels an image is decoded into, whatever its shape (security review, F04-S02). */
    static final long DECODE_BUDGET = UploadedImages.DECODE_BUDGET;

    /** The tallest a cover is kept: a cover taller than wide at 1600 would exceed it and is refused. */
    static final int COVER_MAX_HEIGHT = 1600;

    private BrandImages() {}

    /** A logo or a cover, with what each must be and how each is kept. */
    public enum Kind {
        LOGO(new ImageShape("a logo", 128, 128, 512, 512, true, ImageFormat.PNG)),
        COVER(new ImageShape("a cover", 800, 300, 1600, COVER_MAX_HEIGHT, false, ImageFormat.JPEG));

        private final ImageShape shape;

        Kind(ImageShape shape) {
            this.shape = shape;
        }

        /** The kind's name in requests and storage: {@code logo} or {@code cover}. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String mediaType() {
            return shape.format().mediaType();
        }
    }

    /** What checking an upload gave. */
    public sealed interface Outcome {}

    /** The image as it will be kept. */
    public record Accepted(byte[] bytes, String mediaType, int width, int height) implements Outcome {}

    /** Why it is refused, for a person; never anything of the file itself. */
    public record Refused(String detail) implements Outcome {}

    /**
     * @param content the uploaded file's bytes
     * @param kind logo or cover
     * @param maxBytes the largest file accepted
     * @param maxPixels the most pixels its header may declare
     */
    public static Outcome check(byte[] content, Kind kind, long maxBytes, long maxPixels) {
        return switch (UploadedImages.check(content, kind.shape, maxBytes, maxPixels)) {
            case ImageCheck.Accepted image ->
                new Accepted(image.bytes(), image.mediaType(), image.width(), image.height());
            case ImageCheck.Refused refused -> new Refused(refused.detail());
        };
    }

    /** The height a cover of this size is kept at: scaled to at most 1600 wide. */
    static long keptHeight(int width, int height) {
        return UploadedImages.keptHeight(width, height, Kind.COVER.shape);
    }

    /** How many pixels apart the decoder samples an image of this kind ({@link UploadedImages#decodeFactor}). */
    static int decodeFactor(int width, int height, Kind kind) {
        return UploadedImages.decodeFactor(width, height, kind.shape);
    }

    /** The pixels a subsampled decode produces: every factor-th row and column, rounded up. */
    static long decodedPixels(int width, int height, int factor) {
        return UploadedImages.decodedPixels(width, height, factor);
    }
}
