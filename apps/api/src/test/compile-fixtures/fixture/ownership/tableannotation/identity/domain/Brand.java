package fixture.ownership.tableannotation.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** Maps branding's table from identity. */
@Entity
@Table(name = "salon_brand")
public class Brand {

    @Id
    private UUID tenantId;
}
