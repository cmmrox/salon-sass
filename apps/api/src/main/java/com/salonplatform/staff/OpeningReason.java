package com.salonplatform.staff;

/** Why a branch is open or closed on a day, from the lowest priority to the highest. */
public enum OpeningReason {
    /** The branch has never saved weekly hours: it is closed until it does. */
    NO_WEEKLY_HOURS,
    /** The weekly hours for that weekday decide: open between them, or a weekday the branch closes. */
    WEEKLY_HOURS,
    /** An accepted holiday choice decides: closed, or open for special hours. "Open as usual" is weekly hours. */
    HOLIDAY,
    /** A special closure closes the whole day, whatever the weekly hours or a holiday choice say. */
    CLOSURE
}
