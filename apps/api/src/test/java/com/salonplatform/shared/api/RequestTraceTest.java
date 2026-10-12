package com.salonplatform.shared.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class RequestTraceTest {

    @Test
    void aValidTraceParentGivesItsTraceId() {
        assertThat(RequestTrace.fromTraceParent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "garbage", "00-00000000000000000000000000000000-00f067aa0ba902b7-01",
        "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01",
        "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01" })
    void anInvalidTraceParentGivesNothing(String header) {
        assertThat(RequestTrace.fromTraceParent(header)).isEmpty();
    }

    @Test
    void aNewTraceIdIs32LowercaseHexCharacters() {
        for (int i = 0; i < 100; i++) {
            assertThat(RequestTrace.newTraceId()).matches("^[0-9a-f]{32}$").isNotEqualTo("0".repeat(32));
        }
    }
}
