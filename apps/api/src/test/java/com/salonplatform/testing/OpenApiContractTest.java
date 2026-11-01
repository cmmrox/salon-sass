package com.salonplatform.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The contract validator fails on each kind of mismatch it is there to catch, and passes a true response. */
class OpenApiContractTest {

    private static final String DOCUMENT = """
            {"openapi":"3.1.0","paths":{"/api/v1/thing":{"get":{"responses":{
              "200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Thing"}}}},
              "409":{"content":{"application/problem+json":{"schema":{"type":"object","required":["code"]}}}},
              "default":{"content":{"application/problem+json":{"schema":{"type":"object"}}}}}}}},
             "components":{"schemas":{"Thing":{"type":"object","required":["id","note","at"],
               "properties":{"id":{"type":"string","format":"uuid"},"note":{"type":["string","null"]},
                             "at":{"type":"string","format":"date-time"}}}}}}
            """;

    private final OpenApiContract contract = OpenApiContract.of(DOCUMENT);

    private static final String TRUE_BODY =
            "{\"id\":\"8f3c1a52-7f3e-4a0e-9a57-0f0c6f4b1f11\",\"note\":null,\"at\":\"2026-11-01T10:00:00Z\"}";

    @Test
    void passesAResponseTheDocumentPromises() {
        assertThat(contract.violations("GET", "/api/v1/thing", 200, "application/json", TRUE_BODY))
                .isEmpty();
    }

    @Test
    void failsANullWhereTheSchemaDoesNotAllowOne() {
        String body = TRUE_BODY.replace("\"2026-11-01T10:00:00Z\"", "null");
        assertThat(contract.violations("GET", "/api/v1/thing", 200, "application/json", body))
                .isNotEmpty();
    }

    @Test
    void failsAMissingRequiredField() {
        String body = "{\"id\":\"8f3c1a52-7f3e-4a0e-9a57-0f0c6f4b1f11\",\"note\":null}";
        assertThat(contract.violations("GET", "/api/v1/thing", 200, "application/json", body))
                .isNotEmpty();
    }

    @Test
    void failsAValueOfTheWrongFormat() {
        String body = TRUE_BODY.replace("8f3c1a52-7f3e-4a0e-9a57-0f0c6f4b1f11", "not-a-uuid");
        assertThat(contract.violations("GET", "/api/v1/thing", 200, "application/json", body))
                .isNotEmpty();
    }

    @Test
    void failsAStatusDocumentedOnlyByTheCatchAllDefault() {
        assertThat(contract.violations("GET", "/api/v1/thing", 403, "application/problem+json", "{\"code\":\"x\"}"))
                .containsExactly("status 403 is not documented for GET /api/v1/thing");
    }

    @Test
    void failsAContentTypeTheStatusDoesNotDocument() {
        assertThat(contract.violations("GET", "/api/v1/thing", 409, "application/json", "{\"code\":\"x\"}"))
                .containsExactly("content type 'application/json' is not documented for status 409");
    }

    @Test
    void failsAnOperationTheDocumentDoesNotHave() {
        assertThat(contract.violations("PUT", "/api/v1/thing", 200, "application/json", TRUE_BODY))
                .containsExactly("the document has no operation PUT /api/v1/thing");
    }
}
