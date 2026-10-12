package com.salonplatform.shared.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @ParameterizedTest
    @CsvSource({
        "400, bad_request",
        "404, not_found",
        "405, method_not_allowed",
        "406, not_acceptable",
        "415, unsupported_media_type",
        "418, request_rejected",
        "500, internal_error",
        "502, internal_error",
        "503, service_unavailable",
    })
    void everyStatusHasAStableCode(int status, String code) {
        assertThat(ErrorCode.forStatus(HttpStatusCode.valueOf(status)).code()).isEqualTo(code);
    }
}
