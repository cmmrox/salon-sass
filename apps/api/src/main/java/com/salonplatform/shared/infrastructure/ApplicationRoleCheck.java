package com.salonplatform.shared.infrastructure;

import java.util.Map;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application on a database role that could get round row-level security: a
 * superuser, a role with {@code BYPASSRLS}, or one that owns a table (T-3). Isolation must not
 * depend on every environment being configured right; one that is not fails at start, loudly,
 * instead of serving salons that can see each other.
 */
@Component
class ApplicationRoleCheck implements SmartInitializingSingleton {

    private static final String ROLE = """
            SELECT r.rolname AS name, r.rolsuper AS superuser, r.rolbypassrls AS bypass,
                   (SELECT count(*) FROM pg_catalog.pg_class c
                     WHERE c.relowner = r.oid AND c.relkind IN ('r', 'p')
                       AND c.relnamespace NOT IN ('pg_catalog'::regnamespace, 'information_schema'::regnamespace))
                   AS owned_tables
              FROM pg_catalog.pg_roles r
             WHERE r.rolname = current_user
            """;

    private final JdbcClient jdbc;

    ApplicationRoleCheck(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> role = jdbc.sql(ROLE).query().singleRow();
        Object name = role.get("name");
        if (Boolean.TRUE.equals(role.get("superuser"))) {
            throw refused(name, "is a superuser");
        }
        if (Boolean.TRUE.equals(role.get("bypass"))) {
            throw refused(name, "has BYPASSRLS");
        }
        if (((Number) role.get("owned_tables")).longValue() > 0) {
            throw refused(name, "owns tables");
        }
    }

    private static IllegalStateException refused(Object role, String why) {
        return new IllegalStateException("The application's database role '" + role + "' " + why
                + ", so row-level security would not hold for it. Connect as a role that owns no table, is not a "
                + "superuser and does not have BYPASSRLS (spring.datasource.username); migrations run as the owning "
                + "role (spring.flyway.user). See docs/architecture/multi-tenancy.md.");
    }
}
