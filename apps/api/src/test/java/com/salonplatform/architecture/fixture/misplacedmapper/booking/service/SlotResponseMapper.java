package com.salonplatform.architecture.fixture.misplacedmapper.booking.service;

import org.mapstruct.Mapper;

/** A mapper written beside the use cases instead of in mapper. */
@Mapper
public interface SlotResponseMapper {

    record Slot(String startsAt) {}

    record SlotResponse(String startsAt) {}

    SlotResponse toResponse(Slot slot);
}
