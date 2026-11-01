package com.salonplatform.branding.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The business profile's rules (F04-S01): every field optional, text trimmed with empty meaning "not
 * set", the address up to 200 characters, the description up to 500, each link an https address of up
 * to 200, the map position in range, phone numbers of the configured region and the WhatsApp number a
 * mobile one (decision 084). A field that breaks a rule is reported by its request name, never with its
 * value.
 */
public final class ProfileRules {

    static final int ADDRESS_LENGTH = 200;
    static final int DESCRIPTION_LENGTH = 500;
    static final int LINK_LENGTH = 200;
    private static final BigDecimal LATITUDE_LIMIT = BigDecimal.valueOf(90);
    private static final BigDecimal LONGITUDE_LIMIT = BigDecimal.valueOf(180);
    /** The stored precision of a coordinate: six decimal places, about 0.1 m. */
    private static final int COORDINATE_SCALE = 6;

    private ProfileRules() {}

    /** A field that broke a rule: its name as the request names it, and what is wrong, for a person. */
    public record Violation(String field, String detail) {}

    /** The checked profile, or what is wrong with it. */
    public sealed interface Outcome {}

    /** Every rule held: the draft as it will be stored. */
    public record Accepted(ProfileContent content) implements Outcome {}

    /** One or more fields broke a rule; nothing is to be stored. */
    public record Refused(List<Violation> violations) implements Outcome {}

    /**
     * Applies the rules to a profile sent for a draft.
     *
     * @param input what was sent
     * @param current the draft as it is, whose branches not listed in the input are kept
     * @param phoneRegion the configured region, a two-letter code
     */
    public static Outcome check(ProfileInput input, ProfileContent current, String phoneRegion) {
        List<Violation> violations = new ArrayList<>();
        String description = text(input.description(), DESCRIPTION_LENGTH, "profile.description", violations);
        String instagram = link(input.instagram(), "profile.links.instagram", violations);
        String facebook = link(input.facebook(), "profile.links.facebook", violations);
        String website = link(input.website(), "profile.links.website", violations);
        Map<UUID, BranchContact> branches = new TreeMap<>(current.byBranch());
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < input.branches().size(); i++) {
            ProfileInput.Branch sent = input.branches().get(i);
            String at = "profile.branches[" + i + "].";
            UUID id = branchId(sent.id(), branches.keySet());
            if (id == null) {
                violations.add(new Violation(at + "id", "is not a branch of this salon"));
                continue;
            }
            if (!seen.add(id)) {
                violations.add(new Violation(at + "id", "names a branch already listed"));
                continue;
            }
            String address = text(sent.address(), ADDRESS_LENGTH, at + "address", violations);
            Coordinates location = location(sent.location(), at + "location", violations);
            String phone = phone(sent.phone(), phoneRegion, false, at + "phone", violations);
            String whatsapp = phone(sent.whatsapp(), phoneRegion, true, at + "whatsapp", violations);
            branches.put(id, new BranchContact(id, address, location, phone, whatsapp));
        }
        if (!violations.isEmpty()) {
            return new Refused(List.copyOf(violations));
        }
        return new Accepted(
                new ProfileContent(description, instagram, facebook, website, List.copyOf(branches.values())));
    }

    private static String trimmed(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String text(String value, int limit, String field, List<Violation> violations) {
        String trimmed = trimmed(value);
        if (trimmed != null && trimmed.codePointCount(0, trimmed.length()) > limit) {
            violations.add(new Violation(field, "must be at most " + limit + " characters"));
        }
        return trimmed;
    }

    private static String link(String value, String field, List<Violation> violations) {
        String trimmed = trimmed(value);
        if (trimmed == null) return null;
        if (trimmed.codePointCount(0, trimmed.length()) > LINK_LENGTH) {
            violations.add(new Violation(field, "must be at most " + LINK_LENGTH + " characters"));
        } else if (!isHttpsAddress(trimmed)) {
            violations.add(new Violation(field, "must be an https:// web address"));
        }
        return trimmed;
    }

    private static boolean isHttpsAddress(String value) {
        if (!value.toLowerCase(Locale.ROOT).startsWith("https://")
                || value.chars().anyMatch(Character::isWhitespace)) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null && host.contains(".");
        } catch (URISyntaxException ex) {
            return false;
        }
    }

    private static UUID branchId(String value, Set<UUID> branches) {
        if (value == null) return null;
        try {
            UUID id = UUID.fromString(value);
            return branches.contains(id) && id.toString().equals(value.toLowerCase(Locale.ROOT)) ? id : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Coordinates location(ProfileInput.Location value, String field, List<Violation> violations) {
        if (value == null) return null;
        BigDecimal latitude = value.latitude();
        BigDecimal longitude = value.longitude();
        boolean valid = true;
        if (latitude == null || latitude.abs().compareTo(LATITUDE_LIMIT) > 0) {
            violations.add(new Violation(field + ".latitude", "must be a number from -90 to 90"));
            valid = false;
        }
        if (longitude == null || longitude.abs().compareTo(LONGITUDE_LIMIT) > 0) {
            violations.add(new Violation(field + ".longitude", "must be a number from -180 to 180"));
            valid = false;
        }
        return valid
                ? new Coordinates(
                        latitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP),
                        longitude.setScale(COORDINATE_SCALE, RoundingMode.HALF_UP))
                : null;
    }

    private static String phone(
            String value, String region, boolean mobileOnly, String field, List<Violation> violations) {
        String trimmed = trimmed(value);
        if (trimmed == null) return null;
        return switch (PhoneNumbers.check(trimmed, region, mobileOnly)) {
            case PhoneNumbers.Valid valid -> valid.e164();
            case PhoneNumbers.NotMobile ignored -> {
                violations.add(new Violation(field, "must be a mobile number"));
                yield null;
            }
            case PhoneNumbers.Invalid ignored -> {
                violations.add(new Violation(field, "must be a valid phone number"));
                yield null;
            }
        };
    }
}
