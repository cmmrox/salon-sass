package com.salonplatform.staff.repository;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Staff's {@code SECURITY DEFINER} function on the platform connection, called inside the platform transaction that
 * reads a salon's seats: the platform pool and a definer function, named direct-SQL exceptions
 * (docs/engineering/backend.md). The function is given the salon and sets it for itself.
 */
@Component
@RequiredArgsConstructor
public class StaffPlatformFunctions {

    private final ObjectProvider<PlatformDatabase> platform;

    /** The salon's active team members with no linked account, counted by {@code staff_active_unlinked_member_count}. */
    public long activeUnlinkedMemberCount(UUID salonId) {
        PlatformDatabase database = platform.getObject();
        database.requireTransaction();
        return database.jdbc()
                .sql("SELECT staff_active_unlinked_member_count(?)")
                .param(salonId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }
}
