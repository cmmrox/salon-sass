package com.salonplatform.testing;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Salons made for one test, written as the owner the way the local seed writes them. Each gets a
 * slug of its own, so tests sharing a database never meet each other's salons, or the local seed's.
 */
public final class TestSalons {

    private final JdbcTemplate owner;

    public TestSalons(JdbcTemplate owner) {
        this.owner = owner;
    }

    /** An active salon with a published brand. */
    public TestSalon create(String name, String brandColour) {
        TestSalon salon = createWithoutBrand("active");
        owner.update("INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (?, ?, ?)",
                salon.id(), name, brandColour);
        return new TestSalon(salon.id(), salon.slug(), name, brandColour);
    }

    /** A salon in the registry with this status and no brand row. */
    public TestSalon createWithoutBrand(String status) {
        UUID id = UUID.randomUUID();
        String slug = "t" + id.toString().replace("-", "").substring(0, 12);
        owner.update("INSERT INTO tenant (id, slug, status) VALUES (?, ?, ?)", id, slug, status);
        return new TestSalon(id, slug, null, null);
    }

    /**
     * @param id the tenant id
     * @param slug its slug
     * @param name its published name, when it has a brand
     * @param brandColour its brand colour, when it has a brand
     */
    public record TestSalon(UUID id, String slug, String name, String brandColour) {

        /** {@code <slug>.localhost:<port>}, as a browser sends it. */
        public String host(int port) {
            return slug + ".localhost:" + port;
        }
    }
}
