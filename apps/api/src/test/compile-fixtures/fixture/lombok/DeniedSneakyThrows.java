package fixture.lombok;

import java.io.IOException;
import lombok.SneakyThrows;

public class DeniedSneakyThrows {

    @SneakyThrows
    void read() {
        throw new IOException("hidden");
    }
}
