package com.salonplatform.catalogue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The catalogue's settings ({@code salon.catalogue}).
 *
 * @param images the limits on an uploaded service photo (F05-S01, decision 093)
 */
@ConfigurationProperties("salon.catalogue")
public record CatalogueSettings(Images images) {

    public CatalogueSettings {
        if (images == null) {
            throw new IllegalArgumentException("salon.catalogue.images must be configured");
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
                throw new IllegalArgumentException("salon.catalogue.images limits must be positive");
            }
        }
    }
}
