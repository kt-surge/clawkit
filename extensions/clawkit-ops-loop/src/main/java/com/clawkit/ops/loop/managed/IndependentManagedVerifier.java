package com.clawkit.ops.loop.managed;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Fresh observer per sample; no model claims, cached decision observations or exit codes determine recovery. */
public final class IndependentManagedVerifier {
    @FunctionalInterface public interface ObserverFactory { ManagedObserver open() throws Exception; }
    @FunctionalInterface public interface Sleeper { void sleep(Duration interval) throws InterruptedException; }
    public record Settings(int maxSamples, int consecutiveHealthy, Duration interval, Duration deadline) {
        public Settings(int maxSamples,int consecutiveHealthy,Duration interval) {
            this(maxSamples,consecutiveHealthy,interval,Duration.ofSeconds(120));
        }
        public Settings {
            if (consecutiveHealthy < 3 || maxSamples < consecutiveHealthy || maxSamples > 30)
                throw new IllegalArgumentException("at least three healthy samples and a bounded window required");
            ManagedApplication.requireDuration(interval,Duration.ofMillis(100),Duration.ofSeconds(10));
            ManagedApplication.requireDuration(deadline,Duration.ofSeconds(1),Duration.ofMinutes(3));
        }
        public static Settings defaults() { return new Settings(12,3,Duration.ofSeconds(2)); }
    }
    public record Sample(Instant collectedAt, List<Observation> observations, boolean healthy, String failureType) {}
    public record Result(boolean recovered, List<Sample> samples) {}
    private final Clock clock;
    private final Settings settings;
    private final Sleeper sleeper;
    public IndependentManagedVerifier(Clock clock, Settings settings, Sleeper sleeper) {
        this.clock=clock; this.settings=settings; this.sleeper=sleeper;
    }
    public Result verify(ManagedApplication app, ObserverFactory factory) throws InterruptedException {
        List<Sample> samples = new ArrayList<>();
        Instant expiresAt=clock.instant().plus(settings.deadline());
        int consecutive = 0;
        for (int i=0;i<settings.maxSamples();i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("verification interrupted");
            if (!clock.instant().isBefore(expiresAt)) return new Result(false,List.copyOf(samples));
            List<Observation> observations = new ArrayList<>();
            String failure = null;
            boolean healthy = false;
            try (ManagedObserver observer = factory.open()) {
                var ledger = new DecisionEvidenceLedger(app,clock);
                for (Probe probe : List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS)) {
                    if (!clock.instant().isBefore(expiresAt)) throw new IllegalStateException("verification deadline exceeded");
                    observations.add(ledger.collect(observer,probe).observation());
                }
                healthy = ledger.snapshot().stream().allMatch(e -> e.currentAt(clock.instant()))
                    && observations.get(0).status() == Status.RUNNING
                    && observations.get(1).status() == Status.HEALTHY && observations.get(2).status() == Status.HEALTHY;
            } catch (InterruptedException e) { throw e; }
            catch (Exception e) { failure=e.getClass().getSimpleName(); }
            samples.add(new Sample(clock.instant(),List.copyOf(observations),healthy,failure));
            consecutive=healthy ? consecutive+1 : 0;
            if (consecutive >= settings.consecutiveHealthy()) return new Result(true,List.copyOf(samples));
            if (i+1<settings.maxSamples()) sleeper.sleep(settings.interval());
        }
        return new Result(false,List.copyOf(samples));
    }
}
