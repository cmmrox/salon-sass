package com.salonplatform.branding.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

/**
 * A phone number as a person types it, checked with libphonenumber for one region and written once in
 * E.164 (decision 084, NFR-13): {@code 077 123 4567}, {@code +94771234567} and {@code 0771234567} are the
 * same Sri Lankan mobile number, stored as {@code +94771234567}. The region is configuration, never a
 * literal here.
 */
public final class PhoneNumbers {

    private static final PhoneNumberUtil NUMBERS = PhoneNumberUtil.getInstance();

    private PhoneNumbers() {}

    /** What checking a number gave. */
    public sealed interface Checked {}

    /** A number of the region, in E.164. */
    public record Valid(String e164) implements Checked {}

    /** Not a number of the region, or not a number at all. */
    public record Invalid() implements Checked {}

    /** A number of the region that is not a mobile number, where one is required. */
    public record NotMobile() implements Checked {}

    /**
     * @param text the number as typed, in any common form
     * @param region the region it must belong to, a two-letter code libphonenumber knows
     * @param mobileOnly whether only a mobile number will do (a WhatsApp number)
     */
    public static Checked check(String text, String region, boolean mobileOnly) {
        PhoneNumber number;
        try {
            number = NUMBERS.parse(text, region);
        } catch (NumberParseException ex) {
            return new Invalid();
        }
        if (!NUMBERS.isValidNumberForRegion(number, region)) {
            return new Invalid();
        }
        if (mobileOnly) {
            PhoneNumberType type = NUMBERS.getNumberType(number);
            if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
                return new NotMobile();
            }
        }
        return new Valid(NUMBERS.format(number, PhoneNumberFormat.E164));
    }
}
