package com.clawkit.ops.loop.managed;

import java.time.Instant;
import java.util.Objects;

/** Implementations return normalized, redacted facts, never credentials or raw shell output. */
@FunctionalInterface
public interface ManagedObserver extends AutoCloseable {
    Observation observe(ManagedApplication application, Probe probe) throws Exception;
    @Override default void close() throws Exception {}

    enum Probe { SERVICE, HEALTH, BUSINESS, DEPENDENCIES, LOGS, RESOURCES, CHANGES, METRICS }
    enum Status { RUNNING, STOPPED, RESTARTING, HEALTHY, UNHEALTHY, UNKNOWN }

    record Observation(String targetId, String composeProject, String service, Probe probe,
                       Instant observedAt, Status status, String detail, EvidenceEnvelope.Payload payload) {
        public Observation(String targetId,String composeProject,String service,Probe probe,Instant observedAt,Status status,String detail) {
            this(targetId,composeProject,service,probe,observedAt,status,detail,null);
        }
        public Observation {
            Objects.requireNonNull(targetId);
            Objects.requireNonNull(composeProject);
            Objects.requireNonNull(service);
            Objects.requireNonNull(probe);
            Objects.requireNonNull(observedAt);
            Objects.requireNonNull(status);
            if (payload!=null && (observedAt.isBefore(payload.collection().windowStart())
                    || observedAt.isAfter(payload.collection().windowEnd())))
                throw new IllegalArgumentException("observation outside source window");
            if (detail == null || detail.length() > 1200)
                throw new IllegalArgumentException("normalized observation detail must be bounded");
            boolean serviceStatus = status == Status.RUNNING || status == Status.STOPPED || status == Status.RESTARTING;
            if ((probe == Probe.SERVICE && status != Status.UNKNOWN && !serviceStatus)
                    || (probe != Probe.SERVICE && serviceStatus))
                throw new IllegalArgumentException("status incompatible with probe");
        }
    }
}
