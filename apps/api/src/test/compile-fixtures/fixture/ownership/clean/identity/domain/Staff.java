package fixture.ownership.clean.identity.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "identity_staff")
public class Staff {

    @Id
    private UUID id;
}
