package com.salonplatform.shared.outbox;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a record as an event between modules and names its type: the publishing module, the event and its
 * version, such as {@code identity.CustomerDataErased.v1} (docs/engineering/backend.md, "Events between
 * modules"). The record lives in the publishing module's root package and carries ids and values, never an
 * entity or personal data. A published version is never changed, only superseded by a new record with the
 * next version, because events already in the outbox are read back with the record their subscriber names.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface EventType {

    /** {@code <module>.<Event>.v<version>}. */
    String value();
}
