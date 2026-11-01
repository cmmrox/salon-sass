package com.salonplatform.branding.config;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Branding's settings ({@code salon.branding}).
 *
 * @param phoneRegion the region phone and WhatsApp numbers are checked for, an ISO 3166-1 two-letter
 *     code that libphonenumber knows ({@code LK} by default; decision 084, NFR-13). Startup is refused for
 *     any other value, so a typing mistake cannot silently accept every number or refuse them all.
 * @param images the limits on an uploaded brand image (F04-S02, decision 086)
 */
@ConfigurationProperties("salon.branding")
public record BrandingSettings(String phoneRegion, Images images) {

    public BrandingSettings {
        if (phoneRegion == null
                || !PhoneNumberUtil.getInstance().getSupportedRegions().contains(phoneRegion)) {
            throw new IllegalArgumentException("salon.branding.phone-region must be a supported two-letter region");
        }
        if (images == null) {
            throw new IllegalArgumentException("salon.branding.images must be configured");
        }
    }

    /**
     * @param maxBytes the largest file accepted (5 MB)
     * @param maxPixels the most pixels an image may declare in its header; a larger one is refused before
     *     it is decoded, so a decompression bomb cannot exhaust memory
     * @param concurrentDecodes how many uploads an instance decodes at once; one more waits briefly and is
     *     then refused 503, so simultaneous uploads cannot multiply the memory a decode takes
     */
    public record Images(DataSize maxBytes, long maxPixels, int concurrentDecodes) {

        public Images {
            if (maxBytes == null || maxBytes.toBytes() < 1 || maxPixels < 1 || concurrentDecodes < 1) {
                throw new IllegalArgumentException("salon.branding.images limits must be positive");
            }
        }
    }
}
