package fixture.lombok;

import lombok.With;

public class DeniedWith {

    @With
    private final String name;

    DeniedWith(String name) {
        this.name = name;
    }
}
