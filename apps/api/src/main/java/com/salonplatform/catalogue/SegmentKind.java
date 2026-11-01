package com.salonplatform.catalogue;

/**
 * What a part of a service's time is (decision 074): {@code ACTIVE} occupies the professional, {@code PROCESSING}
 * (while colour or a treatment develops, F17) occupies only what it names, and {@code BUFFER} follows the service
 * and is never shown to customers. R1 creates active and buffer segments only.
 */
public enum SegmentKind {
    ACTIVE,
    PROCESSING,
    BUFFER
}
