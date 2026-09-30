package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.IncidentFlightRecorder;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startable Fixture-only composition for the OPS-3A observe-only loop.
 *
 * <p>This is deliberately not a remote scheduler. It accepts only a
 * {@code fixture-*} target, writes local registry/state files under the caller
 * supplied directory, and fixes the Provider budget at zero. Repairs are not
 * present in this composition.
 */
public final class FixtureObserveOnlyLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FixtureObserveOnlyLoop.class);

    public record Config(
        Path stateDirectory,
        String targetId,
        String serviceId,
        Duration initialDelay,
        Duration scheduleDelay,
        AutomationBudgetPolicy budgetPolicy
    ) {
        public Config {
            stateDirectory = Objects.requireNonNull(stateDirectory, "stateDirectory")
                .toAbsolutePath().normalize();
            if (targetId == null || !targetId.matches("fixture-[a-z0-9][a-z0-9_-]{0,62}")) {
                throw new IllegalArgumentException("Fixture loop targetId must match fixture-[a-z0-9][a-z0-9_-]{0,62}");
            }
            if (serviceId == null || !"order-api".equals(serviceId)) {
                throw new IllegalArgumentException("Fixture loop only supports serviceId order-api");
            }
            initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
            scheduleDelay = Objects.requireNonNull(scheduleDelay, "scheduleDelay");
            budgetPolicy = Objects.requireNonNull(budgetPolicy, "budgetPolicy");
            if (initialDelay.isNegative()) {
                throw new IllegalArgumentException("initialDelay must not be negative");
            }
            if (scheduleDelay.isNegative() || scheduleDelay.isZero()) {
                throw new IllegalArgumentException("scheduleDelay must be positive");
            }
            if (budgetPolicy.maxProviderCalls() != 0) {
                throw new IllegalArgumentException("Fixture observe-only loop requires maxProviderCalls=0");
            }
        }

        public static Config defaults(Path stateDirectory) {
            return new Config(stateDirectory, "fixture-app-down", "order-api",
                Duration.ZERO, Duration.ofSeconds(30),
                AutomationBudgetPolicy.FIXTURE_OBSERVE_ONLY);
        }
    }

    private final Config config;
    private final IncidentRegistry registry;
    private final AutomationStateStore stateStore;
    private final FixtureObservationRunner observationRunner;
    private final ObservationAutomationCoordinator coordinator;
    private boolean started;
    private boolean closed;

    /** Construct the production Fixture loop with a daemon JDK scheduler. */
    public static FixtureObserveOnlyLoop open(Config config) throws IOException {
        return new FixtureObserveOnlyLoop(config, Clock.systemUTC(), new JdkAutomationTaskScheduler(1));
    }

    // Package-visible for deterministic tests. The scheduler still belongs to this loop and is closed with it.
    FixtureObserveOnlyLoop(Config config, Clock clock, AutomationTaskScheduler scheduler)
        throws IOException {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(scheduler, "scheduler");

        IncidentRegistry openedRegistry = null;
        AutomationStateStore openedStateStore = null;
        try {
            openedRegistry = new IncidentRegistry(
                config.stateDirectory().resolve("incident-registry.jsonl"), clock,
                IncidentCooldownPolicy.FIXTURE);
            openedStateStore = new AutomationStateStore(
                config.stateDirectory().resolve("automation-state.json"), clock);
            openedStateStore.setBudgetPolicy(config.budgetPolicy());

            observationRunner = new FixtureObservationRunner(clock);
            IncidentFlightRecorder flightRecorder = new IncidentFlightRecorder(
                config.stateDirectory().resolve("observation-timeline.jsonl"), clock);
            coordinator = new ObservationAutomationCoordinator(scheduler, openedStateStore,
                observationRunner, new ObservationToIncidentBridge(openedRegistry, clock),
                FixtureObserveOnlyLoop::noProviderDiagnosis, clock, config.serviceId(),
                config.initialDelay(), config.scheduleDelay(), false,
                event -> flightRecorder.record("OBSERVATION_" + event.outcome(),
                    "run://" + event.runId(), Map.of(
                        "targetId", event.targetId(),
                        "evidenceRefs", event.evidenceRefs(),
                        "evidenceFacts", event.evidenceFacts(),
                        "diagnosisEnabled", event.diagnosisEnabled(),
                        "providerCalled", event.providerCalled())));
            registry = openedRegistry;
            stateStore = openedStateStore;
        } catch (IOException | RuntimeException e) {
            if (openedStateStore != null) openedStateStore.close();
            if (openedRegistry != null) openedRegistry.close();
            scheduler.close();
            throw e;
        }
    }

    /** Starts recurring Fixture observation. Idempotent. */
    public synchronized void start() throws IOException {
        ensureOpen();
        if (started) return;
        coordinator.start();
        coordinator.scheduleTarget(config.targetId());
        started = true;
    }

    public void setSignal(ObservedSignal signal) {
        ensureOpen();
        observationRunner.setSignal(signal);
    }

    public ObservedSignal signal() {
        return observationRunner.signal();
    }

    public void pause() throws IOException {
        ensureOpen();
        coordinator.pauseTarget(config.targetId());
    }

    public void resume() throws IOException {
        ensureOpen();
        coordinator.resumeTarget(config.targetId());
    }

    public AutomationStatus status() {
        return coordinator.getStatus(config.targetId());
    }

    public AutomationBudgetStatus budgetStatus() {
        return coordinator.getBudgetStatus(config.targetId());
    }

    public List<RegistryEntry> incidents() {
        return registry.snapshot();
    }

    private static DiagnosisRunner.DiagnosisResult noProviderDiagnosis(
        String targetId, com.clawkit.ops.loop.DiscoveryResult discovery
    ) {
        return new DiagnosisRunner.DiagnosisResult("FIXTURE_OBSERVE_ONLY_NO_PROVIDER", false);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Fixture observe-only loop is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        coordinator.close();
        if (!coordinator.awaitIdle(Duration.ZERO)) {
            // Keep the local stores locked rather than allowing a still-running
            // observation to write after their file handles are released.
            log.error("Fixture observe-only loop failed to drain; local stores remain open until process exit");
            return;
        }
        stateStore.close();
        registry.close();
    }
}
