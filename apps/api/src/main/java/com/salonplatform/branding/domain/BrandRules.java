package com.salonplatform.branding.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The brand's rules (F04-S02 AC-6): the name required, 1 to 60 characters after trimming; the tagline
 * optional, up to 80, empty meaning not set; the colour {@code #RRGGBB}, kept in upper case; the logo and
 * the cover each an image of this salon of that kind. A field that breaks a rule is reported by its
 * request name, never with its value.
 */
public final class BrandRules {

    static final int NAME_LENGTH = 60;
    static final int TAGLINE_LENGTH = 80;

    private BrandRules() {}

    /** A brand as sent: text untrimmed, the images as ids given as text (or null for none). */
    public record Input(String name, String tagline, String colour, String logo, String cover) {}

    /** The checked brand, or what is wrong with it. */
    public sealed interface Outcome {}

    public record Accepted(BrandContent content) implements Outcome {}

    public record Refused(List<ProfileRules.Violation> violations) implements Outcome {}

    /**
     * @param input what was sent
     * @param images the salon's images, each id with its kind ({@code logo} or {@code cover})
     */
    public static Outcome check(Input input, Map<UUID, String> images) {
        List<ProfileRules.Violation> violations = new ArrayList<>();
        String name = trimmed(input.name());
        if (name == null) {
            violations.add(new ProfileRules.Violation("brand.name", "is required"));
        } else if (length(name) > NAME_LENGTH) {
            violations.add(new ProfileRules.Violation("brand.name", "must be at most " + NAME_LENGTH + " characters"));
        }
        String tagline = trimmed(input.tagline());
        if (tagline != null && length(tagline) > TAGLINE_LENGTH) {
            violations.add(
                    new ProfileRules.Violation("brand.tagline", "must be at most " + TAGLINE_LENGTH + " characters"));
        }
        String colour = input.colour() == null ? null : input.colour().strip();
        if (colour == null || !colour.matches("^#[0-9A-Fa-f]{6}$")) {
            violations.add(new ProfileRules.Violation("brand.colour", "must be a colour written #RRGGBB"));
        }
        UUID logo = image(input.logo(), "logo", images, "brand.logo", violations);
        UUID cover = image(input.cover(), "cover", images, "brand.cover", violations);
        if (!violations.isEmpty()) {
            return new Refused(List.copyOf(violations));
        }
        return new Accepted(new BrandContent(name, tagline, colour.toUpperCase(Locale.ROOT), logo, cover));
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    /** An image id of this salon and of this kind; another salon's id and an unknown one are the same error. */
    private static UUID image(
            String value,
            String kind,
            Map<UUID, String> images,
            String field,
            List<ProfileRules.Violation> violations) {
        if (value == null) return null;
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value) && kind.equals(images.get(id))) {
                return id;
            }
        } catch (IllegalArgumentException ex) {
            // Falls through to the same answer as an id that names no image.
        }
        violations.add(new ProfileRules.Violation(field, "is not a " + kind + " image of this salon"));
        return null;
    }
}
