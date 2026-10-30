package fixture.mapstruct;

import com.salonplatform.shared.mapping.ResponseMapperConfig;
import org.mapstruct.Mapper;

/** The response has a property, {@code nickname}, that nothing maps. */
@Mapper(config = ResponseMapperConfig.class)
public interface UnmappedTargetMapper {

    record Source(String name) {}

    record Response(String name, String nickname) {}

    Response toResponse(Source source);
}
