package com.salonplatform.staff.domain;

/** What kind of leave it is (F06-S03 AC-1). Private: only a caller with {@code team.manage} receives it (decision 104). */
public enum LeaveType {
    ANNUAL("annual"),
    SICK("sick"),
    PERSONAL("personal"),
    OTHER("other");

    private final String value;

    LeaveType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** The type the API value names, or null when it names none. */
    public static LeaveType of(String value) {
        for (LeaveType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        return null;
    }
}
