package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.automation.FixtureEvidenceSnapshot;
import com.clawkit.ops.loop.automation.FixtureEvidenceFact;
import com.clawkit.ops.loop.automation.FixtureObservationTimelineReader;
import com.clawkit.ops.loop.automation.IncidentRegistry;
import com.clawkit.ops.loop.automation.RegistryEntry;
import com.clawkit.ops.loop.repair.RepairSuggestion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Produces a Fixture-only A3 counterfactual from an already verified A0
 * snapshot. The output is deliberately outside the snapshot and contains no
 * remote session, dispatcher, FixSession, or executable repair action.
 */
public final class FixtureShadowReplayRunner {
    private static final String POLICY_ID = "fixture-shadow-v1";

    private FixtureShadowReplayRunner() { }

    public record Result(
        FixtureEvidenceSnapshot.Result snapshot,
        AutoRemediationPolicy policy,
        ShadowDecisionStore.RecordedDecision recordedDecision,
        Path directory
    ) {
        public Result {
            snapshot = Objects.requireNonNull(snapshot, "snapshot");
            policy = Objects.requireNonNull(policy, "policy");
            recordedDecision = Objects.requireNonNull(recordedDecision, "recordedDecision");
            directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
            if (recordedDecision.decision().sideEffectCalls() != 0) {
                throw new IllegalArgumentException("Fixture Shadow result must have zero side effects");
            }
        }
    }

    /**
     * The policy expiry is derived from immutable snapshot time, rather than
     * run time, so replaying the same snapshot remains idempotent and old
     * evidence naturally degrades to EXPIRED.
     */
    public static Result run(Path snapshotDirectory, Path outputRoot, Clock clock) throws IOException {
        Objects.requireNonNull(snapshotDirectory, "snapshotDirectory");
        Objects.requireNonNull(outputRoot, "outputRoot");
        Objects.requireNonNull(clock, "clock");

        FixtureEvidenceSnapshot.Result snapshot = FixtureEvidenceSnapshot.verify(snapshotDirectory);
        if (!AutoRemediationPolicy.FIXTURE_TARGET.equals(snapshot.targetId())) {
            throw new IOException("A3 Fixture Shadow only accepts fixture-app-down snapshots");
        }
        RegistryEntry active = activeIncident(snapshot.directory(), clock);
        List<FixtureObservationTimelineReader.Entry> timeline =
            FixtureObservationTimelineReader.read(
                snapshot.directory().resolve("observation-timeline.jsonl"), 100);
        Path root = outputRoot.toAbsolutePath().normalize();
        Path directory = root.resolve(snapshot.snapshotId()).normalize();
        if (!directory.startsWith(root)) throw new IOException("Unsafe Shadow output directory");

        AutoRemediationPolicy policy = AutoRemediationPolicy.fixtureAppDownShadow(
            POLICY_ID, snapshot.createdAt().plus(Duration.ofDays(7)));
        ShadowEvaluationRequest request = new ShadowEvaluationRequest(
            active.incidentId(), snapshot.targetId(), sha256(snapshot.directory().resolve(
                FixtureEvidenceSnapshot.MANIFEST_FILE)), active.lastObservedAt(), fixtureDiagnosis(active, timeline),
            new RepairSuggestion(active.incidentId(), AutoRemediationPolicy.RESTART_SERVICE,
                AutoRemediationPolicy.ORDER_API, "fixture-shadow-replay", 0.90,
                active.lastEvidenceRefs()), ModelOpinion.UNSPECIFIED);

        ShadowWorkflow workflow = new ShadowWorkflow(
            new ShadowPolicyStore(directory.resolve("policies")),
            new ShadowDecisionStore(directory.resolve("decisions")), new ShadowPolicyGate(clock));
        ShadowDecisionStore.RecordedDecision decision = workflow.evaluateAndRecord(policy, request);
        return new Result(snapshot, policy, decision, directory);
    }

    private static RegistryEntry activeIncident(Path snapshotDirectory, Clock clock) throws IOException {
        try (IncidentRegistry registry = new IncidentRegistry(
            snapshotDirectory.resolve("incident-registry.jsonl"), clock)) {
            return registry.snapshot().stream()
                .filter(entry -> entry.status() == RegistryEntry.EntryStatus.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new IOException("Fixture snapshot has no ACTIVE Incident for Shadow replay"));
        }
    }

    private static Diagnosis fixtureDiagnosis(
        RegistryEntry active, List<FixtureObservationTimelineReader.Entry> timeline
    ) throws IOException {
        String serviceRef = "fixture://" + active.lastRunId() + "/service-status";
        String httpRef = "fixture://" + active.lastRunId() + "/http-probe";
        if (!active.lastEvidenceRefs().contains(serviceRef)
            || !active.lastEvidenceRefs().contains(httpRef)) {
            throw new IOException("Fixture Shadow requires service-status and http-probe evidence from the active run");
        }
        FixtureObservationTimelineReader.Entry observation = timeline.stream()
            .filter(entry -> entry.runEventReference().equals("run://" + active.lastRunId()))
            .findFirst()
            .orElseThrow(() -> new IOException(
                "Fixture Shadow requires the active run in the verified A0 timeline"));
        if (!observation.evidenceFacts().contains(
                new FixtureEvidenceFact(serviceRef, "SERVICE_STATE", "stopped"))
            || !observation.evidenceFacts().contains(
                new FixtureEvidenceFact(httpRef, "HTTP_STATUS", "503"))) {
            throw new IOException(
                "Fixture Shadow requires verified stopped service and HTTP 503 facts from the active run");
        }
        return new Diagnosis("APP_DOWN", 0.90,
            List.of("service-status", "http-probe"), List.of(), List.of(), List.of(),
            "RESTART_SERVICE", false, "fixture-shadow-v1", Diagnosis.DiagnosisStatus.CONFIRMED,
            Diagnosis.CurrentCondition.ACTIVE, null, Diagnosis.ResolutionAttribution.NONE);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for Fixture Shadow", e);
        }
    }
}
