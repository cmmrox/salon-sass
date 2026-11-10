package com.salonplatform.booking.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Optional;

/**
 * A customer's mobile number (decision 084, NFR-13): typed in any common form ({@code 077 123 4567},
 * {@code 0771234567}, {@code +94771234567}), checked with libphonenumber for the configured region, stored in E.164
 * and shown in the region's national format. A landline or another region's number is not a mobile of the region.
 */
public final class CustomerMobile {

    private static final PhoneNumberUtil NUMBERS = PhoneNumberUtil.getInstance();

    private CustomerMobile() {}

    /** The number in E.164 when it is a mobile number of the region; empty otherwise. */
    public static Optional<String> parse(String text, String region) {
        if (text == null || text.isBlank() || text.length() > 30) {
            return Optional.empty();
        }
        PhoneNumber number;
        try {
            number = NUMBERS.parse(text, region);
        } catch (NumberParseException ex) {
            return Optional.empty();
        }
        if (!NUMBERS.isValidNumberForRegion(number, region)) {
            return Optional.empty();
        }
        PhoneNumberType type = NUMBERS.getNumberType(number);
        if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
            return Optional.empty();
        }
        return Optional.of(NUMBERS.format(number, PhoneNumberFormat.E164));
    }

    /** A stored number as the region writes it: {@code 077 123 4567}. */
    public static String national(String e164) {
        try {
            return NUMBERS.format(NUMBERS.parse(e164, null), PhoneNumberFormat.NATIONAL);
        } catch (NumberParseException ex) {
            throw new IllegalStateException("A stored mobile number is in E.164", ex);
        }
    }
}
