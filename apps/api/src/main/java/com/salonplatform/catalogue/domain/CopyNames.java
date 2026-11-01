package com.salonplatform.catalogue.domain;

import java.util.Locale;
import java.util.Set;

/**
 * The name of a duplicated service (F05-S01 AC-7): "&lt;name&gt; (copy)", or "(copy 2)" and upward when that name
 * is taken, with the base name shortened so that the result fits the service name limit.
 */
public final class CopyNames {

    private CopyNames() {}

    /**
     * Lengths are counted and the base is cut in Unicode characters (code points), as the name limit is checked, so a
     * character outside the Basic Multilingual Plane (an emoji) is kept whole or left out, never split.
     *
     * @param name the service's name
     * @param taken the salon's live service names, in lower case
     */
    public static String of(String name, Set<String> taken) {
        int characters = name.codePointCount(0, name.length());
        for (int copy = 1; ; copy++) {
            String suffix = copy == 1 ? " (copy)" : " (copy " + copy + ")";
            int room = CatalogueLimits.SERVICE_NAME_MAX - suffix.length();
            String base = characters <= room
                    ? name
                    : name.substring(0, name.offsetByCodePoints(0, room)).stripTrailing();
            String candidate = base + suffix;
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }
}
