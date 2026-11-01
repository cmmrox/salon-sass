package com.salonplatform.platform.repository;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The current salon's currency, read through {@code salon_currency()} on the caller's tenant transaction (the
 * tenant pool's {@link JdbcClient} joins it). Named direct-SQL exception 2 of docs/engineering/backend.md: a
 * {@code SECURITY DEFINER} function the tenant role may call, scoped by {@code app.tenant_id}, so it answers
 * for the salon set in that transaction and nothing with no salon set.
 */
@Component
public class SalonCurrencyStore {

    private final JdbcClient tenantJdbc;

    SalonCurrencyStore(JdbcClient tenantJdbc) {
        this.tenantJdbc = tenantJdbc;
    }

    /** The currency of the salon set in the current transaction; empty when it has none. */
    public Optional<String> forCurrentSalon() {
        return tenantJdbc
                .sql("SELECT salon_currency() AS currency")
                .query((row, n) -> Optional.ofNullable(row.getString("currency")))
                .single();
    }
}
