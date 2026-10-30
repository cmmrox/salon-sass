package fixture.mapstruct;

import com.salonplatform.shared.mapping.ResponseMapperConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** The same mapper as {@code UnmappedTargetMapper}, with every response property mapped. */
@Mapper(config = ResponseMapperConfig.class)
public interface MappedTargetMapper {

    record Source(String name) {}

    record Response(String name, String nickname) {}

    @Mapping(target = "nickname", source = "name")
    Response toResponse(Source source);
}
