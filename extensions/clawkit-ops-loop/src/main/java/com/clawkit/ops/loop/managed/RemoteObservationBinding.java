package com.clawkit.ops.loop.managed;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** User-selected read-only source mapping. Contains no SSH host, credential or command. */
public record RemoteObservationBinding(String targetId, String environment, String service,
        String containerId, Endpoint health, Endpoint business, Endpoint metrics, List<String> dependencies) {
    public record Endpoint(String name, URI uri, String marker) {
        public Endpoint {
            ManagedApplication.identifier(name);
            ManagedApplication.requireLocalHttp(uri);
            if (marker == null || marker.length() > 256) throw new IllegalArgumentException("bounded endpoint predicate required");
        }
    }
    public RemoteObservationBinding {
        ManagedApplication.identifier(targetId);
        ManagedApplication.identifier(environment);
        ManagedApplication.identifier(service);
        if (containerId == null || !containerId.matches("(?:[a-f0-9]{12}|[a-f0-9]{64})"))
            throw new IllegalArgumentException("explicit observed container identity required");
        dependencies = List.copyOf(dependencies);
        if (dependencies.size() > 8 || dependencies.stream().distinct().count() != dependencies.size())
            throw new IllegalArgumentException("bounded distinct dependency declarations required");
        dependencies.forEach(ManagedApplication::identifier);
        if (dependencies.contains(service)) throw new IllegalArgumentException("application cannot depend on itself");
        if (business != null && (business.marker().isBlank()
                || health!=null && (business.name().equals(health.name()) || business.uri().equals(health.uri()))
                || metrics!=null && (business.name().equals(metrics.name()) || business.uri().equals(metrics.uri()))
                || business.uri().getPath().matches("(?i).*/(?:health|metrics)(?:/.*)?")))
            throw new IllegalArgumentException("health/metrics cannot replace a business predicate");
    }
    public ManagedApplication application(String id, long version, Duration interval) {
        return new ManagedApplication(id, targetId, environment, service, version, false,
                ManagedApplication.DesiredState.RUNNING, null,
                health == null ? null : health.uri(), business == null ? null : business.uri(),
                business == null ? "business-source-unconfigured" : business.marker(), interval, Duration.ofSeconds(90));
    }
    public void validate(ManagedApplication app) {
        if (!targetId.equals(app.targetId()) || !environment.equals(app.composeProject())
                || !service.equals(app.service()) || app.stateless()
                || !Objects.equals(app.healthUri(), health == null ? null : health.uri())
                || !Objects.equals(app.businessUri(), business == null ? null : business.uri())
                || !Objects.equals(app.businessMarker(), business == null ? "business-source-unconfigured" : business.marker()))
            throw new IllegalArgumentException("read-only remote application binding differs");
    }
}
