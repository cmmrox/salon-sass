package com.salonplatform.shared.images;

import java.awt.Color;
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
 * An uploaded image as checked and remade (F04-S02, decision 086). The content decides what it is, never its name
 * or declared type: it must be a PNG, JPEG or WebP that a reader for that format accepts. Its size is read from
 * the header first, and an image declaring more pixels than the limit is refused before anything is decoded. It is
 * then decoded (subsampled when it is much larger than it will be kept), cropped and resized to its
 * {@link ImageShape}, and re-encoded, so nothing of the original file but its picture survives: no metadata
 * (camera, GPS), no embedded profile, no trailing data. However large or oddly shaped an image is, it is decoded
 * into at most {@link #DECODE_BUDGET} pixels.
 */
public final class UploadedImages {

    /** The formats an upload may be, by the name ImageIO's readers give them. */
    private static final Set<String> ACCEPTED = Set.of("png", "jpeg", "webp");

    private static final float JPEG_QUALITY = 0.85f;

    /**
     * The most pixels an image is decoded into, whatever its shape: about 64 MB as ARGB. The subsampling factor is
     * chosen so that both sides together stay within it (security review, F04-S02).
     */
    public static final long DECODE_BUDGET = 16_000_000L;

    private UploadedImages() {}

    /** The refusal of a file over the limit, for a file refused by its declared size as for one that was read. */
    public static ImageCheck.Refused tooLarge(long maxBytes) {
        return new ImageCheck.Refused("must be at most " + (maxBytes / (1024 * 1024)) + " MB");
    }

    /**
     * @param content the uploaded file's bytes
     * @param shape what it must be and how it is kept
     * @param maxBytes the largest file accepted
     * @param maxPixels the most pixels its header may declare
     */
    public static ImageCheck check(byte[] content, ImageShape shape, long maxBytes, long maxPixels) {
        if (content.length == 0) {
            return new ImageCheck.Refused("must be a PNG, JPEG or WebP image");
        }
        if (content.length > maxBytes) {
            return tooLarge(maxBytes);
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(content))) {
            ImageReader reader = readerFor(input);
            if (reader == null) {
                return new ImageCheck.Refused("must be a PNG, JPEG or WebP image");
            }
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if ((long) width * height > maxPixels) {
                    return new ImageCheck.Refused("has too many pixels");
                }
                if (width < shape.minWidth() || height < shape.minHeight()) {
                    return new ImageCheck.Refused(
                            "must be at least " + shape.minWidth() + " x " + shape.minHeight() + " pixels");
                }
                if (!shape.squareCrop() && keptHeight(width, height, shape) > shape.maxHeight()) {
                    return new ImageCheck.Refused(
                            "is too tall: " + shape.noun() + " is kept at most " + shape.maxHeight() + " pixels high");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int factor = decodeFactor(width, height, shape);
                if (factor > 1) {
                    param.setSourceSubsampling(factor, factor, 0, 0);
                }
                BufferedImage decoded = reader.read(0, param);
                return remade(decoded, shape);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            // A reader that recognised the format but cannot decode the rest: a corrupt or truncated file.
            return new ImageCheck.Refused("could not be read as an image");
        }
    }

    /** The height an image of this size is kept at: scaled to at most the shape's width. */
    public static long keptHeight(int width, int height, ImageShape shape) {
        return width <= shape.maxWidth() ? height : (long) height * shape.maxWidth() / width;
    }

    /**
     * How many pixels apart the decoder samples: at least enough to bring the kept side near twice its kept size
     * (no point decoding more), and at least enough that the decoded image, both sides together, fits
     * {@link #DECODE_BUDGET}, so neither a tall nor a wide image escapes.
     */
    public static int decodeFactor(int width, int height, ImageShape shape) {
        int kept = shape.squareCrop() ? Math.min(width, height) : width;
        int factor = Math.max(1, kept / (shape.maxWidth() * 2));
        while (decodedPixels(width, height, factor) > DECODE_BUDGET) {
            factor++;
        }
        return factor;
    }

    /** The pixels a subsampled decode produces: every factor-th row and column, rounded up. */
    public static long decodedPixels(int width, int height, int factor) {
        return (long) ((width + factor - 1) / factor) * ((height + factor - 1) / factor);
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

    private static ImageCheck remade(BufferedImage decoded, ImageShape shape) throws IOException {
        BufferedImage source = decoded;
        if (shape.squareCrop()) {
            int side = Math.min(source.getWidth(), source.getHeight());
            source = source.getSubimage((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
        }
        int width = Math.min(source.getWidth(), shape.maxWidth());
        int height = Math.max(1, Math.round((float) source.getHeight() * width / source.getWidth()));
        boolean alpha = shape.format() == ImageFormat.PNG;
        BufferedImage target =
                new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                // A JPEG has no transparency: transparent parts become white.
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return new ImageCheck.Accepted(
                encode(target, shape.format()), shape.format().mediaType(), width, height);
    }

    private static byte[] encode(BufferedImage image, ImageFormat format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (format == ImageFormat.PNG) {
            if (!ImageIO.write(image, "png", out)) throw new IOException("no PNG writer");
            return out.toByteArray();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
