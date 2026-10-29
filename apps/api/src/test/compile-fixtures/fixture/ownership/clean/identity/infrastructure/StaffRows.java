package fixture.ownership.clean.identity.infrastructure;

import org.springframework.beans.factory.annotation.Value;

public class StaffRows {

    static final String ACTIVE = "SELECT s FROM Staff s WHERE s.active";

    static final String ERASE = "DELETE FROM identity_staff WHERE tenant_id = ?";

    @Value("${salon.identity.signing-key}")
    private String signingKey;

    @Value("${salon.platform-domain}")
    private String platformDomain;
}
