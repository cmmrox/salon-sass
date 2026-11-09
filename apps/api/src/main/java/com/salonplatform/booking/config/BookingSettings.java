package com.salonplatform.booking.config;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Booking's settings ({@code salon.booking}).
 *
 * @param phoneRegion the region a customer's mobile number on a booking is checked for, an ISO 3166-1 two-letter code
 *     that libphonenumber knows ({@code LK} by default; decision 084, NFR-13). By default it follows branding's
 *     {@code SALON_BRANDING_PHONE_REGION}, so one region applies to the salon's own numbers and its customers'. Startup
 *     is refused for any other value.
 */
@ConfigurationProperties("salon.booking")
public record BookingSettings(String phoneRegion) {

    public BookingSettings {
        if (phoneRegion == null
                || !PhoneNumberUtil.getInstance().getSupportedRegions().contains(phoneRegion)) {
            throw new IllegalArgumentException("salon.booking.phone-region must be a supported two-letter region");
        }
    }
}
