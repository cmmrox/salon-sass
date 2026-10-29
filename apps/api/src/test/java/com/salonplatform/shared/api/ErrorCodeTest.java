package com.salonplatform.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatusCode;

class ErrorCodeTest {

    @ParameterizedTest
    @CsvSource({
        "400, bad_request",
        "404, not_found",
        "405, method_not_allowed",
        "406, not_acceptable",
        "415, unsupported_media_type",
        "403, request_rejected",
        "418, request_rejected",
        "500, internal_error",
        "502, internal_error",
        "503, service_unavailable",
    })
    void everyStatusHasAStableCode(int status, String code) {
        assertThat(ErrorCode.forStatus(HttpStatusCode.valueOf(status)).code()).isEqualTo(code);
    }

    @Test
    void aSuspendedSalonHasACodeOfItsOwnThatNoStatusAloneGives() {
        assertThat(ErrorCode.SALON_SUSPENDED.code()).isEqualTo("salon_suspended");
        for (int status = 400; status <= 599; status++) {
            assertThat(ErrorCode.forStatus(HttpStatusCode.valueOf(status)))
                    .as("%s", status)
                    .isNotEqualTo(ErrorCode.SALON_SUSPENDED);
        }
    }

    @Test
    void everyCodeIsSpelledOnceInSnakeCaseAndHasASentenceForAPerson() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::code))
                .doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[a-z]+(_[a-z]+)*"));
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::detail)).allMatch(detail -> !detail.isBlank());
    }
}
