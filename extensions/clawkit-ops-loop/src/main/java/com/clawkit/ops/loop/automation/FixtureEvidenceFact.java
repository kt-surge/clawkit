package com.clawkit.ops.loop.automation;

import com.clawkit.ops.loop.DiscoveryResult;
import com.clawkit.ops.loop.Evidence;
import com.clawkit.ops.loop.EvidenceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A small, allow-listed projection of a Fixture observation fact.
 *
 * <p>It deliberately preserves the two decision-relevant values for the
 * Fixture APP_DOWN scenario, without copying raw tool output, logs, commands,
 * credentials, or remote connection metadata into the A0 timeline.
 */
public record FixtureEvidenceFact(String reference, String kind, String value) {

    private static final String FIXTURE_REFERENCE =
        "fixture://fixture-observe-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[a-z0-9-]+";

    public FixtureEvidenceFact {
        if (reference == null || !reference.matches(FIXTURE_REFERENCE)) {
            throw new IllegalArgumentException("Fixture evidence fact has an unsafe reference");
        }
        if (!"SERVICE_STATE".equals(kind) && !"HTTP_STATUS".equals(kind)
            && !"COLLECTION_STATUS".equals(kind)) {
            throw new IllegalArgumentException("Fixture evidence fact kind is not allowed");
        }
        if (("SERVICE_STATE".equals(kind) && !("stopped".equals(value) || "running".equals(value)))
            || ("HTTP_STATUS".equals(kind) && !("200".equals(value) || "503".equals(value)))
            || ("COLLECTION_STATUS".equals(kind) && !"unavailable".equals(value))) {
            throw new IllegalArgumentException("Fixture evidence fact value is not allowed");
        }
    }

    /**
     * Convert only the fixed Fixture runner's evidence into a safe projection.
     * Other runners produce no projection rather than widening this format.
     */
    public static List<FixtureEvidenceFact> from(DiscoveryResult discovery) {
        Objects.requireNonNull(discovery, "discovery");
        if (!FixtureObservationRunner.DISCOVERY_PROFILE.equals(discovery.profileName())) return List.of();
        if (discovery.bundle() == null) {
            throw new IllegalArgumentException("Fixture discovery has no evidence bundle");
        }
        List<FixtureEvidenceFact> facts = new ArrayList<>();
        for (Evidence evidence : discovery.bundle().evidence()) {
            String reference = evidence.rawReference();
            if (evidence.type() == EvidenceType.SERVICE_STATUS
                && "service-status".equals(evidence.evidenceId())
                && evidence.fact().path("success").asBoolean(false)) {
                String state = evidence.fact().path("data").path("State").asText();
                facts.add(new FixtureEvidenceFact(reference, "SERVICE_STATE", state));
            } else if (evidence.type() == EvidenceType.HTTP_PROBE
                && "http-probe".equals(evidence.evidenceId())
                && evidence.fact().path("success").asBoolean(false)) {
                int status = evidence.fact().path("data").path("statusCode").asInt(-1);
                facts.add(new FixtureEvidenceFact(reference, "HTTP_STATUS", Integer.toString(status)));
            } else if (evidence.type() == EvidenceType.SERVICE_STATUS
                && "collection-failure".equals(evidence.evidenceId())
                && evidence.collectionStatus() == Evidence.CollectionStatus.COLLECTION_FAILED) {
                facts.add(new FixtureEvidenceFact(reference, "COLLECTION_STATUS", "unavailable"));
            } else {
                throw new IllegalArgumentException("Fixture discovery contains an unsupported evidence shape");
            }
        }
        if (facts.isEmpty()) throw new IllegalArgumentException("Fixture discovery has no projectable facts");
        return List.copyOf(facts);
    }
}
