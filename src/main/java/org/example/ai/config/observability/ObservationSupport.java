package org.example.ai.config.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Small helpers for creating consistently nested business observations. */
public final class ObservationSupport {

    private ObservationSupport() {
    }

    public static <T> T call(ObservationRegistry registry, String name,
                             Consumer<Observation> attributes, Supplier<T> work) {
        Observation observation = Observation.createNotStarted(name, registry);
        if (attributes != null) {
            attributes.accept(observation);
        }
        observation.start();
        try (Observation.Scope ignored = observation.openScope()) {
            return work.get();
        } catch (RuntimeException e) {
            observation.error(e);
            throw e;
        } finally {
            observation.stop();
        }
    }

    public static void run(ObservationRegistry registry, String name,
                           Consumer<Observation> attributes, Runnable work) {
        call(registry, name, attributes, () -> {
            work.run();
            return null;
        });
    }
}
