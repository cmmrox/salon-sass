package com.salonplatform.branding.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * F04-S02 AC-4: an upload is told by its content, size-checked from its header before decoding, and kept
 * re-encoded: a logo as its centre square, a PNG of at most 512 x 512; a cover as a JPEG at most 1600 wide.
 */
public class BrandImagesTest {

    private static final long FIVE_MB = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 50_000_000L;

    public static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x2F6B5E));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(0xD6086A));
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    public static byte[] resource(String name) throws IOException {
        try (InputStream in = BrandImagesTest.class.getResourceAsStream("/branding/" + name)) {
            return in.readAllBytes();
        }
    }

    /** A JPEG with an Exif segment naming a camera and GPS position, and a comment, right after its start. */
    public static byte[] jpegWithMetadata(int width, int height) throws IOException {
        byte[] plain = image(width, height, "jpeg");
        byte[] exif = "Exif\0\0CameraMaker-Secret GPSLatitude-6.9061".getBytes(StandardCharsets.ISO_8859_1);
        byte[] comment = "comment-secret".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, 0, (byte) (exif.length + 2)});
        out.write(exif);
        out.write(new byte[] {(byte) 0xFF, (byte) 0xFE, 0, (byte) (comment.length + 2)});
        out.write(comment);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /** A tiny PNG whose header declares a huge image: a decompression bomb. */
    public static byte[] pngDeclaring(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(
                out,
                "IHDR",
                ByteBuffer.allocate(13)
                        .putInt(width)
                        .putInt(height)
                        .put((byte) 8)
                        .put((byte) 2)
                        .put((byte) 0)
                        .put((byte) 0)
                        .put((byte) 0)
                        .array());
        Deflater deflater = new Deflater();
        deflater.setInput(new byte[1024]);
        deflater.finish();
        byte[] data = new byte[1024];
        int length = deflater.deflate(data);
        chunk(out, "IDAT", java.util.Arrays.copyOf(data, length));
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(data.length).array());
        byte[] name = type.getBytes(StandardCharsets.US_ASCII);
        out.write(name);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.write(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static BrandImages.Outcome check(byte[] content, BrandImages.Kind kind) {
        return BrandImages.check(content, kind, FIVE_MB, MAX_PIXELS);
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void aLogoIsCroppedToItsCentreSquareAndKeptAsAPngOfAtMost512() throws IOException {
        for (String format : new String[] {"png", "jpeg"}) {
            BrandImages.Accepted kept = (BrandImages.Accepted) check(image(1200, 800, format), BrandImages.Kind.LOGO);

            assertThat(kept.mediaType()).isEqualTo("image/png");
            assertThat(kept.width()).isEqualTo(512);
            assertThat(kept.height()).isEqualTo(512);
            BufferedImage decoded = decode(kept.bytes());
            assertThat(decoded.getWidth()).isEqualTo(512);
            assertThat(kept.bytes()[1]).isEqualTo((byte) 'P');
        }
        BrandImages.Accepted small = (BrandImages.Accepted) check(image(200, 300, "png"), BrandImages.Kind.LOGO);
        assertThat(small.width()).isEqualTo(200);
        assertThat(small.height()).isEqualTo(200);
    }

    @Test
    void aCoverIsKeptAsAJpegAtMost1600Wide() throws IOException {
        BrandImages.Accepted kept = (BrandImages.Accepted) check(image(3200, 1200, "png"), BrandImages.Kind.COVER);

        assertThat(kept.mediaType()).isEqualTo("image/jpeg");
        assertThat(kept.width()).isEqualTo(1600);
        assertThat(kept.height()).isEqualTo(600);
        assertThat(kept.bytes()[0]).isEqualTo((byte) 0xFF);
        assertThat(kept.bytes()[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    void aWebpIsReadAndKeptInTheKindsFormat() throws IOException {
        BrandImages.Accepted logo = (BrandImages.Accepted) check(resource("logo-600x400.webp"), BrandImages.Kind.LOGO);
        BrandImages.Accepted cover =
                (BrandImages.Accepted) check(resource("cover-1000x420.webp"), BrandImages.Kind.COVER);

        assertThat(logo.mediaType()).isEqualTo("image/png");
        assertThat(logo.width()).isEqualTo(400);
        assertThat(cover.mediaType()).isEqualTo("image/jpeg");
        assertThat(cover.width()).isEqualTo(1000);
        assertThat(cover.height()).isEqualTo(420);
    }

    @Test
    void reEncodingKeepsNoMetadata() throws IOException {
        byte[] uploaded = jpegWithMetadata(1000, 400);
        assertThat(new String(uploaded, StandardCharsets.ISO_8859_1)).contains("GPSLatitude", "comment-secret");

        BrandImages.Accepted cover = (BrandImages.Accepted) check(uploaded, BrandImages.Kind.COVER);
        BrandImages.Accepted logo = (BrandImages.Accepted) check(uploaded, BrandImages.Kind.LOGO);

        for (byte[] kept : new byte[][] {cover.bytes(), logo.bytes()}) {
            assertThat(new String(kept, StandardCharsets.ISO_8859_1))
                    .doesNotContain("Exif", "GPSLatitude", "CameraMaker", "comment-secret");
        }
    }

    @Test
    void contentThatIsNotAPngJpegOrWebpIsRefusedWhateverItIsCalled() throws IOException {
        byte[] text = "<svg xmlns='http://www.w3.org/2000/svg'/> not an image".getBytes(StandardCharsets.UTF_8);
        byte[] gif = image(300, 300, "gif");
        byte[] bmp = image(300, 300, "bmp");

        for (byte[] content : new byte[][] {text, gif, bmp, new byte[0]}) {
            assertThat(check(content, BrandImages.Kind.LOGO))
                    .isEqualTo(new BrandImages.Refused("must be a PNG, JPEG or WebP image"));
        }
    }

    @Test
    void aLargerFileIsRefusedBeforeItIsRead() {
        byte[] large = new byte[(int) FIVE_MB + 1];
        large[0] = (byte) 0x89;

        assertThat(check(large, BrandImages.Kind.COVER)).isEqualTo(new BrandImages.Refused("must be at most 5 MB"));
    }

    @Test
    void aSmallerImageIsRefused() throws IOException {
        assertThat(check(image(127, 400, "png"), BrandImages.Kind.LOGO))
                .isEqualTo(new BrandImages.Refused("must be at least 128 x 128 pixels"));
        assertThat(check(image(1200, 299, "jpeg"), BrandImages.Kind.COVER))
                .isEqualTo(new BrandImages.Refused("must be at least 800 x 300 pixels"));
    }

    @Test
    void anImageDeclaringMorePixelsThanTheLimitIsRefusedFromItsHeaderAlone() throws IOException {
        byte[] bomb = pngDeclaring(60_000, 60_000);
        assertThat(bomb.length).isLessThan(200);

        assertThat(check(bomb, BrandImages.Kind.COVER)).isEqualTo(new BrandImages.Refused("has too many pixels"));
    }

    @Test
    void anImageThatCannotBeDecodedIsRefused() throws IOException {
        byte[] whole = image(1000, 400, "png");
        byte[] truncated = java.util.Arrays.copyOf(whole, 120);

        assertThat(check(truncated, BrandImages.Kind.COVER))
                .isEqualTo(new BrandImages.Refused("could not be read as an image"));
    }

    // --- the decode budget (security review, F04-S02) ------------------------------------------------------

    @Test
    void aTallNarrowCoverIsRefusedFromItsHeaderBecauseItWouldBeKeptTallerThan1600() throws IOException {
        byte[] tall = pngDeclaring(1000, 40_000);

        assertThat(check(tall, BrandImages.Kind.COVER))
                .isEqualTo(new BrandImages.Refused("is too tall: a cover is kept at most 1600 pixels high"));
        assertThat(check(pngDeclaring(1600, 1601), BrandImages.Kind.COVER))
                .isEqualTo(new BrandImages.Refused("is too tall: a cover is kept at most 1600 pixels high"));
        assertThat(BrandImages.keptHeight(3200, 3200)).isEqualTo(1600);
    }

    @Test
    void aWideShortLogoIsDecodedWithinTheBudget() {
        int factor = BrandImages.decodeFactor(40_000, 1000, BrandImages.Kind.LOGO);

        assertThat(factor).isGreaterThan(1);
        assertThat(BrandImages.decodedPixels(40_000, 1000, factor)).isLessThanOrEqualTo(BrandImages.DECODE_BUDGET);
    }

    @Test
    void everyShapeUnderThePixelLimitIsDecodedWithinTheBudget() {
        int[][] shapes = {
            {1000, 40_000}, {40_000, 1000}, {7000, 7000}, {49_000, 1020}, {1020, 49_000}, {5000, 5000}, {1600, 1600}
        };
        for (int[] shape : shapes) {
            for (BrandImages.Kind kind : BrandImages.Kind.values()) {
                int factor = BrandImages.decodeFactor(shape[0], shape[1], kind);
                assertThat(BrandImages.decodedPixels(shape[0], shape[1], factor))
                        .as("%s %d x %d", kind, shape[0], shape[1])
                        .isLessThanOrEqualTo(BrandImages.DECODE_BUDGET);
            }
        }
        assertThat(BrandImages.decodeFactor(1600, 600, BrandImages.Kind.COVER)).isEqualTo(1);
    }
}
