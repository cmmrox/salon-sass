package com.salonplatform.catalogue;

/**
 * One part of a service's time, in the order the service runs.
 *
 * @param kind what the part is
 * @param minutes how long it lasts, above zero
 */
public record ServiceSegment(SegmentKind kind, int minutes) {}
