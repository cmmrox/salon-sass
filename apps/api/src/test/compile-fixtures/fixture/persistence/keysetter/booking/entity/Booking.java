package fixture.persistence.keysetter.booking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.util.UUID;

/** An entity whose salon can be changed after it was stamped. */
@Entity
public class Booking {

    @Id
    private UUID id;

    private UUID tenantId;

    @Version
    private Long version;

    protected Booking() {}

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
