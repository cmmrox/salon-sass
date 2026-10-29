package fixture.ownership.sharedsetting.shared.secrets;

import org.springframework.beans.factory.annotation.Value;

/** Reads platform's setting from shared. */
public class OperatorKey {

    @Value("${salon.operator.signing-key}")
    private String key;
}
