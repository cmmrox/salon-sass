package com.salonplatform.shared.outbox;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The outbox's part in a salon's closure (PLT-06): every undelivered message of the closed salon is
 * cancelled in the closing platform transaction, through the outbox's own guarded function. Afterwards
 * only {@value #PLATFORM_EMAIL} messages (the platform's own email to the Owner contact, such as the
 * export-ready email) are delivered for a closed salon.
 */
@Service
public class OutboxLifecycle {

    /** The one channel delivered for a closed salon: the platform's own email to its Owner contact. */
    public static final String PLATFORM_EMAIL = "platform-email";

    private final ObjectProvider<PlatformDatabase> database;

    OutboxLifecycle(ObjectProvider<PlatformDatabase> database) {
        this.database = database;
    }

    /** Cancels every pending or in-flight message of a closed salon; returns how many. */
    public int cancelClosedSalon(UUID salonId, Instant at) {
        PlatformDatabase platform = database.getObject();
        platform.requireTransaction();
        Integer cancelled = platform.jdbcTemplate()
                .queryForObject("SELECT outbox_cancel_closed_salon(?, ?)", Integer.class, salonId, Timestamp.from(at));
        return cancelled == null ? 0 : cancelled;
    }
}
