package fixture.ownership.concatsetting.platform.infrastructure;

import org.springframework.core.env.Environment;

/** Reads identity's settings from platform through the environment, by a joined name. */
public class IdentityKeys {

    static String key(Environment environment, String name) {
        return environment.getProperty("salon.identity." + name + "-key");
    }
}
