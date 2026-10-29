package fixture.persistence.publicfield.booking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.util.UUID;

/** An entity whose status anyone can set, past every rule of the booking. */
@Entity
public class Booking {

    @Id
    private UUID id;

    @Version
    private Long version;

    public String status;

    protected Booking() {}
}
