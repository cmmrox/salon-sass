package com.salonplatform.booking.service.impl;

import java.sql.SQLException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Which of the booking table's guarantees a refused write broke, read from the database's own answer. Only these are
 * translated into business outcomes; any other violation stays an error.
 */
enum BookingConstraints {
    /** The exclusion constraint: another live row of the professional overlaps (BKG-05). */
    NO_OVERLAP,
    /** The reference is taken in this salon (BKG-14). */
    REFERENCE,
    /** The idempotency key was used by a request that committed meanwhile. */
    IDEMPOTENCY;

    /** PostgreSQL's SQLSTATE for an exclusion constraint violation. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private static final String UNIQUE_VIOLATION = "23505";

    static Optional<BookingConstraints> of(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String message = String.valueOf(sql.getMessage());
                if (EXCLUSION_VIOLATION.equals(sql.getSQLState())
                        && message.contains("booking_appointment_no_overlap")) {
                    return Optional.of(NO_OVERLAP);
                }
                if (UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                    if (message.contains("\"booking_appointment_reference\"")) {
                        return Optional.of(REFERENCE);
                    }
                    if (message.contains("\"booking_appointment_idempotency\"")) {
                        return Optional.of(IDEMPOTENCY);
                    }
                }
            }
        }
        return Optional.empty();
    }
}
