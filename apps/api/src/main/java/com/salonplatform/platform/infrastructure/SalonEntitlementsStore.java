package com.salonplatform.platform.infrastructure;

import com.salonplatform.shared.entitlements.SalonEntitlements.Limits;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The current salon's plan limits and features, read through {@code salon_entitlements()} on the
 * caller's own tenant transaction (the tenant pool's {@link JdbcClient} joins it). Named direct-SQL
 * exception 2 of docs/engineering/backend.md: a {@code SECURITY DEFINER} function the tenant role may
 * call, scoped by {@code app.tenant_id}, so it sees only the salon set in that transaction and nothing
 * with no salon set; it locks the salon's registry row for share until the transaction ends.
 */
@Component
public class SalonEntitlementsStore {

    private final JdbcClient tenantJdbc;

    SalonEntitlementsStore(JdbcClient tenantJdbc) {
        this.tenantJdbc = tenantJdbc;
    }

    /** The limits of the salon set in the current transaction; empty when it has no plan. */
    public Optional<Limits> forCurrentSalon() {
        return tenantJdbc
                .sql("""
                        SELECT plan_id, staff_seats, branches, message_allowance, custom_domain, dedicated_app
                          FROM salon_entitlements()
                        """)
                .query((row, n) -> row.getObject("plan_id", UUID.class) == null
                        ? Optional.<Limits>empty()
                        : Optional.of(new Limits(
                                row.getInt("staff_seats"),
                                row.getInt("branches"),
                                row.getInt("message_allowance"),
                                row.getBoolean("custom_domain"),
                                row.getBoolean("dedicated_app"))))
                .list()
                .stream()
                .findFirst()
                .flatMap(limits -> limits);
    }
}
