package com.salonplatform.branding.config;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Branding's settings ({@code salon.branding}).
 *
 * @param phoneRegion the region phone and WhatsApp numbers are checked for, an ISO 3166-1 two-letter
 *     code that libphonenumber knows ({@code LK} by default; decision 084, NFR-13). Startup is refused for
 *     any other value, so a typing mistake cannot silently accept every number or refuse them all.
 */
@ConfigurationProperties("salon.branding")
public record BrandingSettings(String phoneRegion) {

    public BrandingSettings {
        if (phoneRegion == null
                || !PhoneNumberUtil.getInstance().getSupportedRegions().contains(phoneRegion)) {
            throw new IllegalArgumentException("salon.branding.phone-region must be a supported two-letter region");
        }
    }
}
