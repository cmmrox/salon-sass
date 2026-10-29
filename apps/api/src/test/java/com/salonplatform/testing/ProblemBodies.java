package com.salonplatform.testing;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/** Helpers for comparing problem details bodies. */
public final class ProblemBodies {

    private ProblemBodies() {}

    /**
     * The body without its {@code traceId}, which is every request's own: what two answers must
     * share when they are "the same answer".
     */
    public static String withoutTraceId(String body) {
        DocumentContext document = JsonPath.parse(body);
        document.delete("$.traceId");
        return document.jsonString();
    }
}
