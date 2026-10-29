package fixture.ownership.clean.branding.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "salon_brand")
public class Brand {

    @Id
    private UUID tenantId;
}
