package fixture.persistence.repository.booking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.util.UUID;

/** An entity that keeps its fields to itself and changes only through what it does. */
@Entity
public class Booking {

    @Id
    private UUID id;

    private UUID tenantId;

    @Version
    private Long version;

    private String status;

    protected Booking() {}

    public static Booking held(UUID id) {
        Booking booking = new Booking();
        booking.id = id;
        booking.status = "held";
        return booking;
    }

    public void confirm() {
        status = "confirmed";
    }

    public UUID getId() {
        return id;
    }

    public String getStatus() {
        return status;
    }
}
