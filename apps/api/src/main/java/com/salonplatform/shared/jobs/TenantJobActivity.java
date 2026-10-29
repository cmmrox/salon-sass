package com.salonplatform.shared.jobs;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How this instance's recurring jobs have treated each salon since it started: the occurrences that
 * came due for the salon, those that ran for it, and those skipped because it was suspended (a closed
 * or deleted salon is neither run nor skipped: its jobs have stopped). Counts only, nothing of what a
 * job did; kept in memory, for the local background-state hook (F03-S03) and nothing else.
 */
@Component
public class TenantJobActivity {

    /** One salon's counts and the last time each moved. */
    public record Counts(long due, long run, long skipped, long failed, Instant lastRunAt, Instant lastSkippedAt) {

        static final Counts NONE = new Counts(0, 0, 0, 0, null, null);
    }

    private final Map<UUID, Counts> salons = new ConcurrentHashMap<>();

    void ran(UUID salon, boolean succeeded, Instant at) {
        salons.merge(
                salon,
                new Counts(1, succeeded ? 1 : 0, 0, succeeded ? 0 : 1, at, null),
                (old, add) -> new Counts(
                        old.due() + 1,
                        old.run() + add.run(),
                        old.skipped(),
                        old.failed() + add.failed(),
                        at,
                        old.lastSkippedAt()));
    }

    void skipped(UUID salon, Instant at) {
        salons.merge(
                salon,
                new Counts(1, 0, 1, 0, null, at),
                (old, add) ->
                        new Counts(old.due() + 1, old.run(), old.skipped() + 1, old.failed(), old.lastRunAt(), at));
    }

    /** The salon's counts; zero for a salon no occurrence has come due for. */
    public Counts of(UUID salon) {
        return salons.getOrDefault(salon, Counts.NONE);
    }

    /** Forgets every salon's counts (the local reset). */
    public void clear() {
        salons.clear();
    }
}
