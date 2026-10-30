package fixture.lombok;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Every annotation the allow-list permits on a Spring bean. */
@Slf4j
@RequiredArgsConstructor
public class AllowedBean {

    private final AllowedEntity entity;

    void describe() {
        log.info("{} {}", entity.getId(), entity.getNote());
    }
}
