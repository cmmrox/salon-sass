package com.salonplatform.platform.domain;

/** Bounded plain text: trimmed, counted in code points, with no control characters unless allowed. */
public final class PlainText {

    private PlainText() {}

    /** The trimmed value when it is 1 to max code points of text without control characters; else null. */
    public static String line(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()
                || trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return trimmed;
    }

    /** Multi-line plain text: line breaks and tabs allowed, other control characters not. */
    public static String block(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()
                || trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            return null;
        }
        return trimmed;
    }
}
