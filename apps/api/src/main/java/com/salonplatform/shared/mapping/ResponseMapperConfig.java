package com.salonplatform.shared.mapping;

import org.mapstruct.InjectionStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * The configuration every MapStruct mapper names ({@code @Mapper(config = ResponseMapperConfig.class)}):
 * the mapper is a Spring bean with constructor injection, and a response property that nothing maps, or
 * a conversion that can lose information, fails the build ({@code docs/engineering/backend.md}, "Code
 * generation").
 */
@MapperConfig(
        componentModel = MappingConstants.ComponentModel.SPRING,
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        typeConversionPolicy = ReportingPolicy.ERROR)
public interface ResponseMapperConfig {}
