package com.salonplatform.shared.infrastructure;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Numeric and boolean JSON are never silently interpreted as credential/device text. */
class StrictJsonConfigurationTest {
    record Text(String deviceLabel) {}

    @Test
    void textRejectsNumericAndBooleanInputButPreservesLiteralText() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new StrictJsonConfiguration().strictTextFields().customize(builder);
        JsonMapper mapper = builder.build();
        for (String value : new String[] {"1", "1.5", "true", "false"})
            assertThatThrownBy(() -> mapper.readValue("{\"deviceLabel\":" + value + "}", Text.class))
                    .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertThat(mapper.readValue("{\"deviceLabel\":\"1\"}", Text.class).deviceLabel())
                .isEqualTo("1");
    }
}
