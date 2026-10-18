package com.salonplatform.shared.api;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathsTest {

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/api/v1/meta", "/api/v1/operator/plans", "/api/v1/operator/salons/"
            + "3f1c2b9a-0000-4000-8000-000000000001", "/local/operator/seed", "/api/v1/operator/"})
    void aCanonicalPathIsItsOwnDispatchedPath(String uri) {
        assertThat(RequestPaths.canonical(request(uri))).isTrue();
        assertThat(RequestPaths.path(request(uri))).isEqualTo(uri);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/operator;x=1/plans", "/api/v1/operator/plans;jsessionid=1",
        "/api/v1/%6Fperator/plans", "/api/v1/operator%2Fplans", "//api/v1/operator/plans", "/api//v1/operator",
        "/api/v1/./operator/plans", "/api/v1/x/../operator/plans", "/api/v1/operator/..", "/api/v1/operator/.",
        "/local/%6Fperator/seed", "/local;a/operator/seed", "/api\\v1/operator", "api/v1/operator", ""})
    void anythingElseIsNeverCanonicalAndNeverUnderAPrefix(String uri) {
        assertThat(RequestPaths.canonical(request(uri))).isFalse();
        assertThat(RequestPaths.path(request(uri))).isNull();
        assertThat(RequestPaths.under(request(uri), "/api/v1/operator")).isFalse();
        assertThat(RequestPaths.under(request(uri), "/local")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/operatorx", "/api/v1/operators/plans", "/localhost/x"})
    void aPrefixMatchesWholeSegmentsOnly(String uri) {
        assertThat(RequestPaths.under(request(uri), "/api/v1/operator")).isFalse();
        assertThat(RequestPaths.under(request(uri), "/local")).isFalse();
    }

    @org.junit.jupiter.api.Test
    void anExportDownloadTokenNeverReachesALogLineOrASpan() {
        String token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd";
        org.assertj.core.api.Assertions.assertThat(RequestPaths.loggable("/api/v1/exports/" + token))
                .isEqualTo("/api/v1/exports/{token}");
        org.assertj.core.api.Assertions.assertThat(RequestPaths.loggable("/api/v1/exports/" + token + "/metadata"))
                .isEqualTo("/api/v1/exports/{token}/metadata");
        org.assertj.core.api.Assertions.assertThat(RequestPaths.loggable("http://localhost:8090/api/v1/exports/" + token))
                .isEqualTo("http://localhost:8090/api/v1/exports/{token}");
        org.assertj.core.api.Assertions.assertThat(RequestPaths.loggable("/api/v1/salon")).isEqualTo("/api/v1/salon");
        org.assertj.core.api.Assertions.assertThat(RequestPaths.loggable(null)).isNull();
    }
}
