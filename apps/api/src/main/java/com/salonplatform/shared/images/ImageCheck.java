package com.salonplatform.shared.images;

/** What checking an upload gave: the image as it will be kept, or why it is refused. */
public sealed interface ImageCheck {

    /** The image as it will be kept, made by the API itself. */
    record Accepted(byte[] bytes, String mediaType, int width, int height) implements ImageCheck {}

    /** Why it is refused, for a person; never anything of the file itself. */
    record Refused(String detail) implements ImageCheck {}
}
