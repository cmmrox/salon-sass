package fixture.ownership.clean.platform.infrastructure;

import org.springframework.core.env.Environment;

public class OperatorKeys {

    static String key(Environment environment, String name) {
        return environment.getProperty("salon.operator." + name + "-key");
    }

    static String salons() {
        return "SELECT id FROM tenant JOIN plan ON plan.id = tenant.plan_id";
    }
}
