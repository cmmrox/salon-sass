package com.salonplatform.shared.images;

import java.io.IOException;

/**
 * An uploaded file before it is read (F05-S01 AC-20): what the request declares its size to be, and how to read it. A
 * use case checks the caller, the target and the declared size first, and reads the bytes only after them.
 *
 * @param declaredSize the file's size as the request gives it, in bytes
 * @param content reads the file's bytes
 */
public record ImageUpload(long declaredSize, Content content) {

    /** Reads the file's bytes. */
    @FunctionalInterface
    public interface Content {
        byte[] read() throws IOException;
    }

    /** Whether the file declares more than the largest accepted, so that it need not be read. */
    public boolean declaresOver(long maxBytes) {
        return declaredSize > maxBytes;
    }

    public byte[] read() throws IOException {
        return content.read();
    }
}
