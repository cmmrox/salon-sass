package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.outbox.OutboxLifecycle;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
class OutboxLifecycleImpl implements OutboxLifecycle {

    private final ObjectProvider<PlatformDatabase> database;

    OutboxLifecycleImpl(ObjectProvider<PlatformDatabase> database) {
        this.database = database;
    }

    @Override
    public int cancelClosedSalon(UUID salonId, Instant at) {
        PlatformDatabase platform = database.getObject();
        platform.requireTransaction();
        return platform.jdbc()
                .sql("SELECT outbox_cancel_closed_salon(?, ?)")
                .params(salonId, Timestamp.from(at))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }
}
