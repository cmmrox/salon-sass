package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.outbox.EventType;
import java.util.regex.Pattern;

/** The type name of an event record, read from its {@link EventType} and checked to carry its version. */
final class EventTypes {

    /** The outbox channel of every event: no provider delivers it, the subscribers do. */
    static final String CHANNEL = "event";

    private static final Pattern TYPE_NAME = Pattern.compile("[a-z]+\\.[A-Z][A-Za-z0-9]*\\.v[1-9][0-9]*");

    private EventTypes() {}

    /** {@code identity.CustomerDataErased.v1}, for a record annotated with that type name. */
    static String nameOf(Class<?> event) {
        EventType type = event.getAnnotation(EventType.class);
        if (!event.isRecord() || type == null) {
            throw new IllegalArgumentException(
                    event.getName() + " is not an event: an event is a record annotated with @EventType");
        }
        if (!TYPE_NAME.matcher(type.value()).matches()) {
            throw new IllegalArgumentException("The event type of " + event.getName() + " is '" + type.value()
                    + "'; it must be <module>.<Event>.v<version>");
        }
        return type.value();
    }
}
