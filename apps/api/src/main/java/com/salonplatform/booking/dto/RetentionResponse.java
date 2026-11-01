package com.salonplatform.booking.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.salonplatform.booking.Retention;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a late cancellation or a no-show keeps: {@code {kind: "nothing"}} (late cancellation only),
 * {@code {kind: "deposit"}} or {@code {kind: "share", percent}}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "RetentionRule",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"kind"})
public record RetentionResponse(
        @Schema(allowableValues = {"nothing", "deposit", "share"})
        String kind,

        @Schema(minimum = "1", maximum = "100", description = "With `share`: the share of the price kept.")
        Integer percent) {

    static RetentionResponse from(Retention retention) {
        return switch (retention) {
            case Retention.Nothing _ -> new RetentionResponse("nothing", null);
            case Retention.Deposit _ -> new RetentionResponse("deposit", null);
            case Retention.Share share -> new RetentionResponse("share", share.percent());
        };
    }
}
