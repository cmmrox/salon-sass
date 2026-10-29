package com.salonplatform.architecture.fixture.unconfiguredmapper.booking.mapper;

import org.mapstruct.Mapper;

/** A mapper that does not name ResponseMapperConfig. */
@Mapper
public interface SlotResponseMapper {

    record Slot(String startsAt) {}

    record SlotResponse(String startsAt) {}

    SlotResponse toResponse(Slot slot);
}
