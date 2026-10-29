package com.salonplatform.identity.mapper;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.dto.PrincipalResponse;
import com.salonplatform.shared.mapping.ResponseMapperConfig;
import org.mapstruct.Mapper;

/** The principal a provided interface returns, in the shape the API answers with. */
@Mapper(config = ResponseMapperConfig.class)
public interface PrincipalResponseMapper {

    PrincipalResponse toResponse(Principal principal);
}
