package com.salonplatform.testing;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.springframework.mock.web.MockMultipartFile;

/**
 * A multipart file that counts how often its bytes are read, for a test that a refused upload is never read
 * (F05-S01 AC-20). Its declared size is the real one.
 */
public final class TrackedUpload extends MockMultipartFile {

    private int reads;

    public TrackedUpload(byte[] content) {
        super("file", "upload.png", "image/png", content);
    }

    /** How many times anything has asked for the file's bytes. */
    public int reads() {
        return reads;
    }

    @Override
    public byte[] getBytes() throws IOException {
        reads++;
        return super.getBytes();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        reads++;
        return super.getInputStream();
    }

    @Override
    public void transferTo(File dest) throws IOException {
        reads++;
        super.transferTo(dest);
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        reads++;
        super.transferTo(dest);
    }
}
