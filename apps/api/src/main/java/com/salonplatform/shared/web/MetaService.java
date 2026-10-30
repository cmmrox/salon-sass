package com.salonplatform.shared.web;

/**
 * Describes the running service. The version and the commit are written by the build
 * ({@code META-INF/build-info.properties} and {@code git.properties}); neither is a property
 * someone edits. When the application runs without them, for example straight from an IDE, each
 * is reported as {@value #UNKNOWN}.
 */
public interface MetaService {

    /** What is reported for a version or commit the build did not write. */
    String UNKNOWN = "unknown";

    /** The service's name, version and commit, and the time it answered. */
    ServiceMeta describe();
}
