package com.salonplatform.platform.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"reference", "amount", "receivedOn"})
public record RecordTransferRequest(
        @Schema(maxLength = 120) String reference,
        @Schema(minimum = "0", maximum = "100000000") Long amount,
        @Schema(format = "date") String receivedOn) {
    @JsonAnySetter
    void unknown(String field, Object ignored) {
        throw UnknownField.refused(field);
    }
}
