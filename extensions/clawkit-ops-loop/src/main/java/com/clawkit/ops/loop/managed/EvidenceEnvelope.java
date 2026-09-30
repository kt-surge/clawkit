package com.clawkit.ops.loop.managed;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Source facts carry collection limits and time windows; a controller assigns their identity. */
public record EvidenceEnvelope(String evidenceId, String applicationId, long applicationVersion,
                               String environment, ManagedObserver.Observation observation,
                               String contentHash) {
    public enum Source { DOCKER_INSPECT, DOCKER_LOGS, DOCKER_STATS, HTTP, CHANGE_IMPORT, PROMETHEUS, LEGACY }
    public enum Quality { COMPLETE, MISSING, ERROR, TRUNCATED, LEGACY }
    public record Collection(Source source, Quality quality, Instant windowStart, Instant windowEnd,
                             Instant collectedAt, String unit, String aggregation, String limitation,
                             String rawReference) {
        public Collection {
            Objects.requireNonNull(source); Objects.requireNonNull(quality);
            Objects.requireNonNull(windowStart); Objects.requireNonNull(windowEnd); Objects.requireNonNull(collectedAt);
            if (windowStart.isAfter(windowEnd) || windowEnd.isAfter(collectedAt))
                throw new IllegalArgumentException("invalid evidence collection window");
            if (unit == null || unit.length()>40 || aggregation == null || aggregation.length()>80
                    || limitation == null || limitation.length()>400
                    || rawReference != null && (!rawReference.matches("[a-zA-Z0-9._/-]{1,160}") || rawReference.contains("..") || rawReference.startsWith("/")))
                throw new IllegalArgumentException("bounded collection metadata required");
            if (quality == Quality.TRUNCATED && limitation.isBlank())
                throw new IllegalArgumentException("truncation must be explained");
        }
    }
    public record ResourceFacts(boolean oomKilled, int exitCode, Instant finishedAt,
                                Long memoryUsageBytes, Long memoryLimitBytes) {
        public ResourceFacts {
            if (memoryUsageBytes != null && memoryUsageBytes<0 || memoryLimitBytes != null && memoryLimitBytes<0)
                throw new IllegalArgumentException("nonnegative resource bytes required");
        }
    }
    public record ChangeRecord(String id, String applicationId, long applicationVersion, String environment,
                               String service, Instant occurredAt, String configurationVersion,
                               String summary, String operator) {
        public ChangeRecord {
            ManagedApplication.identifier(id); ManagedApplication.identifier(applicationId);
            Objects.requireNonNull(occurredAt);
            if (applicationVersion<1 || environment==null || environment.isBlank() || environment.length()>100
                    || service==null || service.isBlank() || service.length()>80
                    || configurationVersion==null || configurationVersion.isBlank() || configurationVersion.length()>80
                    || summary==null || summary.isBlank() || summary.length()>1000
                    || operator==null || operator.isBlank() || operator.length()>100)
                throw new IllegalArgumentException("bounded change identity and summary required");
        }
        public boolean matches(ManagedApplication app) {
            return applicationId.equals(app.id()) && applicationVersion==app.version()
                && environment.equals(app.composeProject()) && service.equals(app.service());
        }
    }
    public record MetricSample(Instant at, String metric, double value, String unit) {
        public MetricSample {
            Objects.requireNonNull(at);
            if (metric==null || !metric.matches("[a-zA-Z0-9_:]{1,80}") || !Double.isFinite(value)
                    || unit==null || unit.length()>40) throw new IllegalArgumentException("finite named metric required");
        }
    }
    public record Payload(Collection collection, ResourceFacts resources, List<ChangeRecord> changes,
                          List<MetricSample> metrics) {
        public Payload {
            Objects.requireNonNull(collection);
            changes=List.copyOf(changes); metrics=List.copyOf(metrics);
            if (changes.size()>20 || metrics.size()>240) throw new IllegalArgumentException("evidence payload exceeds item limit");
            if (changes.stream().anyMatch(c -> c.occurredAt().isBefore(collection.windowStart()) || c.occurredAt().isAfter(collection.windowEnd()))
                    || metrics.stream().anyMatch(m -> m.at().isBefore(collection.windowStart()) || m.at().isAfter(collection.windowEnd())))
                throw new IllegalArgumentException("source facts outside collection window");
        }
        public static Payload snapshot(Source source,Quality quality,Instant at,String limitation) {
            return new Payload(new Collection(source,quality,at,at,at,"status","snapshot",limitation,null),null,List.of(),List.of());
        }
    }
    public EvidenceEnvelope {
        Objects.requireNonNull(evidenceId); Objects.requireNonNull(applicationId); Objects.requireNonNull(observation);
        if (applicationVersion<1 || environment==null || !environment.equals(observation.composeProject())
                || contentHash==null || !contentHash.equals(ManagedContracts.hash(observation)))
            throw new IllegalArgumentException("evidence envelope identity/hash mismatch");
    }
    static EvidenceEnvelope of(String id,ManagedApplication app,ManagedObserver.Observation observation) {
        return new EvidenceEnvelope(id,app.id(),app.version(),app.composeProject(),observation,ManagedContracts.hash(observation));
    }
    public Quality quality() { return observation.payload()==null ? Quality.LEGACY : observation.payload().collection().quality(); }
}
