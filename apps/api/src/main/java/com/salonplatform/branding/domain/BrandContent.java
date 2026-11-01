package com.salonplatform.branding.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a salon's brand holds, in its draft or as published (F04-S02): its name, tagline, colour and the
 * ids of its logo and cover images. The tagline and both images are optional (null when not set).
 *
 * @param name the salon's display name
 * @param tagline its tagline
 * @param colour its brand colour, {@code #RRGGBB} in upper case
 * @param logo its logo image
 * @param cover its cover image
 */
public record BrandContent(String name, String tagline, String colour, UUID logo, UUID cover) {

    /** The prototype's colour, which "Reset to default branding" puts back (F04-S02 AC-5). */
    public static final String DEFAULT_COLOUR = "#D6086A";

    /** The default branding: this name kept, the default colour, and no tagline, logo or cover. */
    public BrandContent reset() {
        return new BrandContent(name, null, DEFAULT_COLOUR, null, null);
    }

    /** The names of the fields that differ from another brand (never their values), in a stable order. */
    public List<String> changedFrom(BrandContent other) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(name, other.name)) changed.add("name");
        if (!Objects.equals(tagline, other.tagline)) changed.add("tagline");
        if (!Objects.equals(colour, other.colour)) changed.add("colour");
        if (!Objects.equals(logo, other.logo)) changed.add("logo");
        if (!Objects.equals(cover, other.cover)) changed.add("cover");
        return List.copyOf(changed);
    }
}
