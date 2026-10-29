package fixture.lombok;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Every annotation the allow-list permits on an entity. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllowedEntity {

    private String id;

    @Setter
    private String note;
}
