package com.salonplatform.staff.dto;

import com.salonplatform.staff.service.TeamMemberService.AccountView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The salon's active sign-in accounts, oldest first, whole: the sheet offers every free one. */
@Schema(
        name = "TeamSignInAccountList",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"items"})
public record SignInAccountListResponse(List<SignInAccountResponse> items) {

    public static SignInAccountListResponse from(List<AccountView> views) {
        return new SignInAccountListResponse(
                views.stream().map(SignInAccountResponse::from).toList());
    }
}
