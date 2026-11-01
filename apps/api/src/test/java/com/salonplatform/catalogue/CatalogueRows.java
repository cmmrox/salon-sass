package com.salonplatform.catalogue;

import com.salonplatform.testing.TestSalons.TestSalon;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Catalogue rows written as the owner, with the salon set for row-level security, for the isolation tests. */
final class CatalogueRows {

    private CatalogueRows() {}

    /**
     * A service of the salon's first category with a photo and its first terms version: Rs. 2,500, 45 minutes and
     * a 10-minute buffer. Its id.
     */
    static UUID serviceWithPhoto(JdbcTemplate owner, TestSalon salon, UUID branch, String name) {
        UUID photo = UUID.randomUUID();
        UUID service = UUID.randomUUID();
        UUID terms = UUID.randomUUID();
        owner.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("BEGIN");
                statement.execute("SELECT set_config('app.tenant_id', '" + salon.id() + "', true)");
                statement.execute("INSERT INTO catalogue_photo (tenant_id, id, branch_id, media_type, width, height,"
                        + " bytes, created_at, updated_at) VALUES ('" + salon.id() + "', '" + photo + "', '" + branch
                        + "', 'image/jpeg', 320, 320, '\\xffd8ffd9'::bytea, now(), now())");
                statement.execute("INSERT INTO catalogue_service (tenant_id, id, branch_id, category_id, name,"
                        + " description, bookable_online, photo_id, current_terms_id, version, created_at, updated_at)"
                        + " SELECT '" + salon.id() + "', '" + service + "', '" + branch + "', c.id, '" + name
                        + "', NULL, true, '" + photo + "', '" + terms + "', 0, now(), now()"
                        + " FROM catalogue_category c WHERE c.tenant_id = '" + salon.id() + "' AND c.position = 1");
                statement.execute("INSERT INTO catalogue_terms_version (tenant_id, id, branch_id, service_id, number,"
                        + " price_minor, currency, segment_kinds, segment_minutes, created_by, created_at, updated_at)"
                        + " VALUES ('" + salon.id() + "', '" + terms + "', '" + branch + "', '" + service + "', 1,"
                        + " 250000, 'LKR', '{active,buffer}', '{45,10}', '" + UUID.randomUUID() + "',"
                        + " '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')");
                statement.execute("COMMIT");
            }
            return null;
        });
        return service;
    }
}
