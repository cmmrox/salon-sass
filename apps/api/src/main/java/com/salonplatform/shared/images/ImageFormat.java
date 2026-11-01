package com.salonplatform.shared.images;

/** How an image is kept: a PNG keeps transparency; a JPEG has none, so transparent parts become white. */
public enum ImageFormat {
    PNG("image/png"),
    JPEG("image/jpeg");

    private final String mediaType;

    ImageFormat(String mediaType) {
        this.mediaType = mediaType;
    }

    public String mediaType() {
        return mediaType;
    }
}
