package com.salonplatform.shared.web;

import java.time.Instant;

/**
 * What the running service says about itself.
 *
 * @param service the service's name
 * @param version the version the build was given
 * @param commit the commit the build was made from
 * @param time the server's time, a UTC instant
 */
public record ServiceMeta(String service, String version, String commit, Instant time) {}
