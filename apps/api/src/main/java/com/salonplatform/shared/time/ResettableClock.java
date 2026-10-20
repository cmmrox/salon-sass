package com.salonplatform.shared.time;

/**
 * A clock that can be returned to real time: the {@code local} test clock. Lets a local hook outside
 * {@code shared.local} (the F03-S03 local reset) put the clock back without depending on that package.
 */
public interface ResettableClock {

    /** Back to real time. */
    void reset();
}
