package com.salonplatform.architecture.fixture.clean.booking.service;

/**
 * A record used only inside its class is private. The cases of a sealed result type stay visible as one
 * contract, even a case that only this class names.
 */
public class HoldSlot {

    public sealed interface Outcome permits Held, Taken {}

    public record Held(String startsAt) implements Outcome {}

    public record Taken() implements Outcome {}

    private record Window(String startsAt) {}

    public Outcome hold(String startsAt) {
        Window window = new Window(startsAt);
        return window.startsAt().isEmpty() ? new Taken() : new Held(window.startsAt());
    }
}
