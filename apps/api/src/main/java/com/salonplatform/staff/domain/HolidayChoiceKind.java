package com.salonplatform.staff.domain;

/** What a salon does on a published holiday. */
public enum HolidayChoiceKind {
    /** Closed all day. */
    CLOSED("closed"),
    /** Open as the weekly hours say: the holiday changes nothing. */
    OPEN("open"),
    /** Open, for special hours given with the choice. */
    SPECIAL("special");

    private final String value;

    HolidayChoiceKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** The kind the API value names, or null when it names none. */
    public static HolidayChoiceKind of(String value) {
        for (HolidayChoiceKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        return null;
    }
}
