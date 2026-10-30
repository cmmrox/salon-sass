package fixture.mapstruct;

import com.salonplatform.shared.mapping.ResponseMapperConfig;
import org.mapstruct.Mapper;

/** A minor-unit amount narrowed from {@code long} to {@code int}, which can lose information. */
@Mapper(config = ResponseMapperConfig.class)
public interface LossyConversionMapper {

    record Source(long amount) {}

    record Response(int amount) {}

    Response toResponse(Source source);
}
