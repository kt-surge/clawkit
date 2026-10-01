package com.clawkit.ops.loop.managed;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** User-owned identity and intent. Model arguments never select a target. */
public record ManagedApplication(String id, String targetId, String composeProject,
        String service, long version, boolean stateless, DesiredState desiredState,
        Instant maintenanceUntil, URI healthUri, URI businessUri, String businessMarker,
        Duration checkInterval, Duration evidenceTtl) {
    public enum DesiredState { RUNNING, STOPPED }

    public ManagedApplication {
        id = identifier(id);
        targetId = identifier(targetId);
        composeProject = identifier(composeProject);
        service = identifier(service);
        if (version < 1) throw new IllegalArgumentException("application version must be positive");
        Objects.requireNonNull(desiredState, "desiredState");
        if (stateless || healthUri != null) requireLocalHttp(healthUri);
        if (stateless || businessUri != null) requireLocalHttp(businessUri);
        if (businessMarker == null || businessMarker.isBlank() || businessMarker.length() > 256)
            throw new IllegalArgumentException("a bounded business response marker is required");
        requireDuration(checkInterval, Duration.ofSeconds(1), Duration.ofHours(1));
        requireDuration(evidenceTtl, Duration.ofSeconds(1), Duration.ofMinutes(10));
    }

    public boolean repairIntendedAt(Instant now) {
        return stateless && desiredState == DesiredState.RUNNING
            && (maintenanceUntil == null || !now.isBefore(maintenanceUntil));
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_-]{0,62}"))
            throw new IllegalArgumentException("invalid registered identifier");
        return value;
    }

    static void requireDuration(Duration value, Duration min, Duration max) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0)
            throw new IllegalArgumentException("duration outside supported range");
    }

    static void requireLocalHttp(URI uri) {
        if (uri == null || !"http".equals(uri.getScheme())
                || uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535
                || !java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null)
            throw new IllegalArgumentException("first release requires a loopback HTTP endpoint without credentials/query");
    }
}
