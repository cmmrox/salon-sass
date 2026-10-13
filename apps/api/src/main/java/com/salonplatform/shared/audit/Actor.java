package com.salonplatform.shared.audit;

import java.util.Objects;

/**
 * Who did what an audit entry records.
 *
 * @param kind the kind of actor
 * @param id the actor's id: a user's id, or the name of the platform's own work
 */
public record Actor(ActorKind kind, String id) {

    public Actor {
        Objects.requireNonNull(kind, "kind");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("an actor has an id");
        }
    }

    public static Actor system(String name) {
        return new Actor(ActorKind.SYSTEM, name);
    }
}
