package com.salonplatform.branding.domain;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * A brand image as uploaded, checked and remade (F04-S02, decision 086). The content decides what it is,
 * never its name or declared type: it must be a PNG, JPEG or WebP that a reader for that format accepts.
 * Its size is read from the header first, and an image declaring more pixels than the limit is refused
 * before anything is decoded. It is then decoded (subsampled when it is much larger than it will be
 * kept), resized and re-encoded, so nothing of the original file but its picture survives: no metadata
 * (camera, GPS), no embedded profile, no trailing data.
 *
 * <p>A logo is cropped to its centre square and kept as a PNG of at most 512 x 512, at least 128 x 128 as
 * uploaded. A cover is kept as a JPEG at most 1600 wide and 1600 high, at least 800 x 300 as uploaded; a
 * cover whose shape would keep it taller than 1600 is refused. However large or oddly shaped an image is,
 * it is decoded into at most {@link #DECODE_BUDGET} pixels.
 */
public final class BrandImages {

    /** The formats an upload may be, by the name ImageIO's readers give them. */
    private static final Set<String> ACCEPTED = Set.of("png", "jpeg", "webp");

    private static final float COVER_QUALITY = 0.85f;

    /**
     * The most pixels an image is decoded into, whatever its shape: about 64 MB as ARGB. The subsampling
     * factor is chosen so that both sides together stay within it (security review, F04-S02).
     */
    static final long DECODE_BUDGET = 16_000_000L;

    /** The tallest a cover is kept: a cover taller than wide at 1600 would exceed it and is refused. */
    static final int COVER_MAX_HEIGHT = 1600;

    private BrandImages() {}

    /** A logo or a cover, with what each must be and how each is kept. */
    public enum Kind {
        LOGO(128, 128, 512, "image/png"),
        COVER(800, 300, 1600, "image/jpeg");

        private final int minWidth;
        private final int minHeight;
        private final int maxWidth;
        private final String mediaType;

        Kind(int minWidth, int minHeight, int maxWidth, String mediaType) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
            this.maxWidth = maxWidth;
            this.mediaType = mediaType;
        }

        /** The kind's name in requests and storage: {@code logo} or {@code cover}. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        public String mediaType() {
            return mediaType;
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
        if (content.length == 0) {
            return new Refused("must be a PNG, JPEG or WebP image");
        }
        if (content.length > maxBytes) {
            return new Refused("must be at most " + (maxBytes / (1024 * 1024)) + " MB");
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            ImageReader reader = readerFor(input);
            if (reader == null) {
                return new Refused("must be a PNG, JPEG or WebP image");
            }
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > maxPixels) {
                    return new Refused("has too many pixels");
                }
                if (width < kind.minWidth || height < kind.minHeight) {
                    return new Refused("must be at least " + kind.minWidth + " x " + kind.minHeight + " pixels");
                }
                if (kind == Kind.COVER && keptHeight(width, height) > COVER_MAX_HEIGHT) {
                    return new Refused("is too tall: a cover is kept at most " + COVER_MAX_HEIGHT + " pixels high");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int factor = decodeFactor(width, height, kind);
                if (factor > 1) {
                    param.setSourceSubsampling(factor, factor, 0, 0);
                }
                BufferedImage decoded = reader.read(0, param);
                return remade(decoded, kind);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            // A reader that recognised the format but cannot decode the rest: a corrupt or truncated file.
            return new Refused("could not be read as an image");
        }
    }

    /** The reader the content's own signature selects, when it is one of the three accepted formats. */
    private static ImageReader readerFor(ImageInputStream input) throws IOException {
        if (input == null) return null;
        Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            String format = reader.getFormatName().toLowerCase(Locale.ROOT);
            if (ACCEPTED.contains(format) || format.equals("jpg")) {
                return reader;
            }
            reader.dispose();
        }
        return null;
    }

    /** The height a cover of this size is kept at: scaled to at most 1600 wide. */
    static long keptHeight(int width, int height) {
        return width <= Kind.COVER.maxWidth ? height : (long) height * Kind.COVER.maxWidth / width;
    }

    /**
     * How many pixels apart the decoder samples: at least enough to bring the kept side near twice its kept
     * size (no point decoding more), and at least enough that the decoded image, both sides together, fits
     * {@link #DECODE_BUDGET}, so neither a tall nor a wide image escapes.
     */
    static int decodeFactor(int width, int height, Kind kind) {
        int kept = kind == Kind.LOGO ? Math.min(width, height) : width;
        int factor = Math.max(1, kept / (kind.maxWidth * 2));
        while (decodedPixels(width, height, factor) > DECODE_BUDGET) {
            factor++;
        }
        return factor;
    }

    /** The pixels a subsampled decode produces: every factor-th row and column, rounded up. */
    static long decodedPixels(int width, int height, int factor) {
        return (long) ((width + factor - 1) / factor) * ((height + factor - 1) / factor);
    }

    private static Outcome remade(BufferedImage decoded, Kind kind) throws IOException {
        BufferedImage source = decoded;
        if (kind == Kind.LOGO) {
            int side = Math.min(source.getWidth(), source.getHeight());
            source = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        }
        int width = Math.min(source.getWidth(), kind.maxWidth);
        int height = Math.max(1, Math.round((float) source.getHeight() * width / source.getWidth()));
        boolean alpha = kind == Kind.LOGO;
        BufferedImage target =
                new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                // A cover is a JPEG, which has no transparency: transparent parts become white.
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return new Accepted(encode(target, kind), kind.mediaType, width, height);
    }

    private static byte[] encode(BufferedImage image, Kind kind) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (kind == Kind.LOGO) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
            return out.toByteArray();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(COVER_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
