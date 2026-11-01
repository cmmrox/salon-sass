package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.TeamMemberService.AccountView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** An active sign-in account of the salon, and who it is linked to. */
@Schema(
        name = "TeamSignInAccount",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "label", "role", "memberId"})
public record SignInAccountResponse(
        @Schema(format = "uuid") UUID id,
        @Schema(description = "Its email, else its mobile.") String label,

        @Schema(description = "Its role, such as `MANAGER`.")
        String role,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                description = "The team member linked to it; null for none.")
        UUID memberId) {

    public static SignInAccountResponse from(AccountView view) {
        return new SignInAccountResponse(view.id(), view.label(), view.role(), view.memberId());
    }
}
