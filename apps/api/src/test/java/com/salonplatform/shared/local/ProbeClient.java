package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives the local hooks of a started application, as QA does from outside. */
final class ProbeClient {

    static final String ALPHA = "alpha";

    static final String BETA = "beta";

    static final String GAMMA = "gamma";

    private final StartedApplication application;

    ProbeClient(StartedApplication application) {
        this.application = application;
    }

    /** The settings that start an application with the local hooks and the dispatcher on. */
    static String[] localWithDispatcher(String... more) {
        String[] base = { "--spring.profiles.active=local", "--salon.outbox.enabled=true", "--salon.jobs.enabled=false",
            "--salon.outbox.poll-interval=100ms" };
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    String host(String salon) {
        return salon + ".localhost:" + application.port();
    }

    /** Writes a probe on the salon's hostname and returns its id. */
    String probe(String salon, String body) {
        Exchange created = application.http().postJsonOnHost(host(salon), "/local/probes", body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return JsonPath.parse(created.body()).read("$.id");
    }

    DocumentContext view(String salon, String id) {
        Exchange response = application.http().getOnHost(host(salon), "/local/probes/" + id);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return JsonPath.parse(response.body());
    }

    /** Waits up to the limit for the probe to satisfy the condition, and returns it then. */
    DocumentContext awaitProbe(String salon, String id, Duration limit, Predicate<DocumentContext> condition) {
        Instant until = Instant.now().plus(limit);
        DocumentContext probe = view(salon, id);
        while (!condition.test(probe)) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("the probe did not get there within " + limit + ": " + probe.jsonString());
            }
            sleep(50);
            probe = view(salon, id);
        }
        return probe;
    }

    void setClock(Instant instant) {
        Exchange response = application.http().sendOnHost("PUT", "localhost:" + application.port(), "/local/clock",
                Map.of("Content-Type", "application/json"), "{\"instant\":\"" + instant + "\"}");
        assertThat(response.status()).isEqualTo(200);
    }

    Instant clock() {
        return Instant.parse(JsonPath.parse(application.http().sendOnHost("GET", "localhost:" + application.port(),
                "/local/clock", Map.of(), null).body()).read("$.now"));
    }

    static Predicate<DocumentContext> status(String status) {
        return probe -> status.equals(probe.read("$.status"));
    }

    static Predicate<DocumentContext> attempts(int attempts) {
        return probe -> probe.<Integer>read("$.attempts") >= attempts && "pending".equals(probe.read("$.status"))
                || probe.<Integer>read("$.attempts") >= attempts && !"sending".equals(probe.read("$.status"));
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
