package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.TaxSettingsService.History;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A page of the tax settings' versions, newest first. */
@Schema(
        name = "CatalogueTaxHistory",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items", "page", "size", "total"})
public record TaxHistoryResponse(
        List<TaxHistoryEntryResponse> items,

        @Schema(minimum = "0", description = "The page, from 0.")
        int page,

        @Schema(minimum = "1", maximum = "100") int size,

        @Schema(minimum = "0", description = "How many versions there are.")
        long total) {

    public static TaxHistoryResponse from(History history) {
        return new TaxHistoryResponse(
                history.items().stream().map(TaxHistoryEntryResponse::from).toList(),
                history.page(),
                history.size(),
                history.total());
    }
}
