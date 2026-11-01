package com.salonplatform.catalogue.dto;

import com.salonplatform.catalogue.service.PopularSettings.PopularView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** How the Popular list is made, and the list it makes now. */
@Schema(
        name = "CataloguePopularSetting",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"mode", "version", "popular"})
public record PopularSettingResponse(
        @Schema(
                allowableValues = {"chosen", "most_booked"},
                description = "`chosen`: the services the salon chose. `most_booked`: the most booked of the last 90"
                        + " days, the chosen ones until bookings exist.")
        String mode,

        @Schema(description = "Its version, which a change is made from; 0 before the first change.", minimum = "0")
        long version,

        @ArraySchema(
                maxItems = 6,
                arraySchema = @Schema(description = "The Popular services' ids in order, among those bookable online."),
                schema = @Schema(format = "uuid"))
        List<UUID> popular) {

    public static PopularSettingResponse from(PopularView view) {
        return new PopularSettingResponse(view.mode(), view.version(), view.popular());
    }
}
