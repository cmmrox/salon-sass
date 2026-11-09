package com.salonplatform.staff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Staff's settings ({@code salon.staff}).
 *
 * @param images the limits on an uploaded team member photo (F06-S01)
 */
@ConfigurationProperties("salon.staff")
public record StaffSettings(Images images) {

    public StaffSettings {
        if (images == null) {
            throw new IllegalArgumentException("salon.staff.images must be configured");
        }
    }

    /**
     * @param maxBytes the largest file accepted (5 MB)
     * @param maxPixels the most pixels an image may declare in its header; a larger one is refused before it is
     *     decoded, so a decompression bomb cannot exhaust memory
     * @param concurrentDecodes how many photos an instance decodes at once; one more waits briefly and is then
     *     refused 503
     */
    public record Images(DataSize maxBytes, long maxPixels, int concurrentDecodes) {

        public Images {
            if (maxBytes == null || maxBytes.toBytes() < 1 || maxPixels < 1 || concurrentDecodes < 1) {
                throw new IllegalArgumentException("salon.staff.images limits must be positive");
            }
        }
    }
}
