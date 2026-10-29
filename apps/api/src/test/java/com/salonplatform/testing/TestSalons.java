package com.salonplatform.testing;

import java.time.ZoneId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Salons made for one test, written as the owner the way the local seed writes them. Each gets a
 * slug of its own, so tests sharing a database never meet each other's salons, or the local seed's.
 */
public final class TestSalons {

    /** The time zone a salon has unless it is given another: the column's default. */
    public static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("Asia/Colombo");

    private final JdbcTemplate owner;

    public TestSalons(JdbcTemplate owner) {
        this.owner = owner;
    }

    /** An active salon with a published brand. */
    public TestSalon create(String name, String brandColour) {
        TestSalon salon = createWithoutBrand("active");
        owner.update(
                "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (?, ?, ?)",
                salon.id(),
                name,
                brandColour);
        return new TestSalon(salon.id(), salon.slug(), name, brandColour);
    }

    /** An active salon with a published brand, in this time zone. */
    public TestSalon create(String name, String brandColour, ZoneId timeZone) {
        TestSalon salon = create(name, brandColour);
        owner.update("UPDATE tenant SET time_zone = ? WHERE id = ?", timeZone.getId(), salon.id());
        return salon;
    }

    /** A salon in the registry with this status and no brand row. */
    public TestSalon createWithoutBrand(String status) {
        UUID id = UUID.randomUUID();
        String slug = "t" + id.toString().replace("-", "").substring(0, 12);
        owner.update("INSERT INTO tenant (id, slug, status) VALUES (?, ?, ?)", id, slug, status);
        return new TestSalon(id, slug, null, null);
    }

    /** Suspends a salon, the way an operator will: its status in the registry, and nothing else. */
    public void suspend(TestSalon salon) {
        setStatus(salon, "suspended");
    }

    public void activate(TestSalon salon) {
        setStatus(salon, "active");
    }

    /** The salon as the tenant context holds it, for work done for it outside a request. */
    public com.salonplatform.shared.tenancy.CurrentTenant asCurrent(TestSalon salon) {
        String zone = owner.queryForObject("SELECT time_zone FROM tenant WHERE id = ?", String.class, salon.id());
        return new com.salonplatform.shared.tenancy.CurrentTenant(salon.id(), salon.slug(), ZoneId.of(zone));
    }

    private void setStatus(TestSalon salon, String status) {
        int changed = owner.update("UPDATE tenant SET status = ?, updated_at = now() WHERE id = ?", status, salon.id());
        if (changed != 1) {
            throw new IllegalStateException("no salon " + salon.slug() + " to set " + status);
        }
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
