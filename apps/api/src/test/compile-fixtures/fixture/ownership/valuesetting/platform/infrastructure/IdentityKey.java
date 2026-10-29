package fixture.ownership.valuesetting.platform.infrastructure;

import org.springframework.beans.factory.annotation.Value;

/** Reads identity's setting from platform through a placeholder. */
public class IdentityKey {

    @Value("${salon.identity.signing-key}")
    private String key;
}
