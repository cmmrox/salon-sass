package fixture.persistence.clean.booking.repository;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Set-based work, a named direct-SQL exception, in infrastructure. */
public class ExpiredHolds {

    private final JdbcClient jdbc;

    public ExpiredHolds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public int remove(UUID tenantId) {
        return jdbc.sql("DELETE FROM booking WHERE tenant_id = ? AND status = 'expired'")
                .param(tenantId)
                .update();
    }
}
