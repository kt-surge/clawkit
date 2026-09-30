package com.clawkit.ops.loop.autonomy;

import com.clawkit.ops.loop.Diagnosis;
import com.clawkit.ops.loop.repair.RepairSuggestion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic 100-case, Fixture-only A3 contract matrix. It creates only
 * local policy/decision evidence; it owns no remote session or repair client.
 */
public final class FixtureShadowEvaluationRunner {
    public static final String REPORT_FILE = "a3-fixture-evaluation-report.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String FOOTER = "# SHA-256: ";
    private static final List<String> INVARIANTS = List.of(
        "fixture_only", "one_action_allowlist", "side_effect_calls_zero",
        "recovered_and_insufficient_evidence_hold_ask", "unsupported_actions_rejected",
        "expired_policy_never_eligible");

    private FixtureShadowEvaluationRunner() { }

    public record Counts(int total, int eligibleShadow, int askRequired, int rejected, int expired) { }

    public record Report(
        int schemaVersion, String mode, Instant executedAt, String targetId,
        Counts counts, Map<String, Integer> reasonCounts, int sideEffectCalls,
        boolean passed, List<String> verifiedInvariants
    ) {
        public Report {
            reasonCounts = Map.copyOf(new TreeMap<>(Objects.requireNonNull(reasonCounts, "reasonCounts")));
            verifiedInvariants = List.copyOf(Objects.requireNonNull(verifiedInvariants, "verifiedInvariants"));
        }
    }

    public record Result(Report report, Path directory, boolean created) {
        public Result {
            report = Objects.requireNonNull(report, "report");
            directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        }
    }

    public static Result run(Path outputDirectory, Clock clock) throws IOException {
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        Objects.requireNonNull(clock, "clock");
        Path directory = outputDirectory.toAbsolutePath().normalize();
        Instant now = clock.instant();
        AutoRemediationPolicy eligible = policy("fixture-shadow-eval", now.plus(Duration.ofHours(1)), true);
        AutoRemediationPolicy disabled = policy("fixture-shadow-disabled", now.plus(Duration.ofHours(1)), false);
        AutoRemediationPolicy expired = policy("fixture-shadow-expired", now, true);
        ShadowDecisionStore decisions = new ShadowDecisionStore(directory.resolve("decisions"));
        ShadowWorkflow workflow = new ShadowWorkflow(
            new ShadowPolicyStore(directory.resolve("policies")), decisions, new ShadowPolicyGate(clock));

        List<ShadowDecision> recorded = new ArrayList<>(100);
        int caseNumber = 1;
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 20, CaseKind.ELIGIBLE, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 15, CaseKind.RECOVERED, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 15, CaseKind.STALE, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 10, CaseKind.WRONG_TARGET, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 10, CaseKind.WRONG_ACTION, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 10, CaseKind.DB_LOCK_WAIT, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 8, CaseKind.INSUFFICIENT, now);
        caseNumber = runCases(recorded, workflow, eligible, caseNumber, 6, CaseKind.MODEL_OPPOSES, now);
        caseNumber = runCases(recorded, workflow, disabled, caseNumber, 3, CaseKind.ELIGIBLE, now);
        caseNumber = runCases(recorded, workflow, expired, caseNumber, 3, CaseKind.ELIGIBLE, now);
        if (caseNumber != 101) throw new IllegalStateException("Fixture Shadow matrix must contain exactly 100 cases");

        Report report = summarize(now, recorded);
        Path reportPath = directory.resolve(REPORT_FILE);
        boolean created = writeOrVerify(reportPath, report);
        return new Result(report, directory, created);
    }

    public static Report verifyReport(Path reportPath) throws IOException {
        String payload = readVerified(reportPath);
        try {
            Report report = MAPPER.readValue(payload, Report.class);
            if (report.schemaVersion() != 1 || !"FIXTURE_SYNTHETIC_CONTRACT_MATRIX".equals(report.mode())
                || report.counts().total() != 100 || !report.passed()
                || report.sideEffectCalls() != 0 || !INVARIANTS.equals(report.verifiedInvariants())) {
                throw new IOException("A3 Fixture evaluation report contract is invalid");
            }
            return report;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("A3 Fixture evaluation report JSON is invalid", e);
        }
    }

    private static int runCases(List<ShadowDecision> recorded, ShadowWorkflow workflow,
                                AutoRemediationPolicy policy, int start, int amount,
                                CaseKind kind, Instant now) throws IOException {
        for (int number = start; number < start + amount; number++) {
            recorded.add(workflow.evaluateAndRecord(policy, request(number, kind, now)).decision());
        }
        return start + amount;
    }

    private static ShadowEvaluationRequest request(int number, CaseKind kind, Instant now) {
        String incidentId = "eval-inc-" + String.format("%03d", number);
        String target = kind == CaseKind.WRONG_TARGET ? "fixture-other" : "fixture-app-down";
        String action = kind == CaseKind.WRONG_ACTION ? "restart_cache" : "restart_service";
        String rootCause = kind == CaseKind.DB_LOCK_WAIT ? "DB_LOCK_WAIT" : "APP_DOWN";
        Diagnosis.CurrentCondition condition = kind == CaseKind.RECOVERED
            ? Diagnosis.CurrentCondition.RECOVERED : Diagnosis.CurrentCondition.ACTIVE;
        List<String> supporting = kind == CaseKind.INSUFFICIENT ? List.of()
            : List.of("fixture-service-status-" + number, "fixture-http-probe-" + number);
        List<String> missing = kind == CaseKind.INSUFFICIENT ? List.of("service-status") : List.of();
        Instant observedAt = kind == CaseKind.STALE ? now.minus(Duration.ofMinutes(6)) : now;
        ModelOpinion opinion = kind == CaseKind.MODEL_OPPOSES ? ModelOpinion.OPPOSES_ACTION : ModelOpinion.SUPPORTS_ACTION;
        Diagnosis diagnosis = new Diagnosis(rootCause, 0.90, supporting, List.of(), List.of(), missing,
            action, condition == Diagnosis.CurrentCondition.RECOVERED, "1", Diagnosis.DiagnosisStatus.CONFIRMED,
            condition, observedAt, Diagnosis.ResolutionAttribution.NONE);
        RepairSuggestion suggestion = new RepairSuggestion(incidentId, action, "order-api", "fixture-matrix",
            0.90, supporting);
        return new ShadowEvaluationRequest(incidentId, target, sha256("fixture-evaluation-" + number), observedAt,
            diagnosis, suggestion, opinion);
    }

    private static AutoRemediationPolicy policy(String id, Instant expiry, boolean enabled) {
        return new AutoRemediationPolicy(1, id, AutonomyLevel.A3_SHADOW, "FIXTURE", "fixture-app-down",
            "fixture-observe-only", "restart_service", "order-api", 1, 100, expiry, enabled);
    }

    private static Report summarize(Instant now, List<ShadowDecision> decisions) {
        Map<String, Integer> reasons = new TreeMap<>();
        int eligible = 0, ask = 0, rejected = 0, expired = 0, sideEffects = 0;
        for (ShadowDecision decision : decisions) {
            reasons.merge(decision.reasonCodes().getFirst(), 1, Integer::sum);
            sideEffects += decision.sideEffectCalls();
            switch (decision.outcome()) {
                case ELIGIBLE_SHADOW -> eligible++;
                case ASK_REQUIRED -> ask++;
                case REJECTED -> rejected++;
                case EXPIRED -> expired++;
            }
        }
        Counts counts = new Counts(decisions.size(), eligible, ask, rejected, expired);
        boolean passed = counts.total() == 100 && eligible == 20 && ask == 47 && rejected == 30 && expired == 3
            && sideEffects == 0;
        return new Report(1, "FIXTURE_SYNTHETIC_CONTRACT_MATRIX", now, "fixture-app-down", counts,
            reasons, sideEffects, passed, INVARIANTS);
    }

    private static boolean writeOrVerify(Path reportPath, Report report) throws IOException {
        String payload = MAPPER.writeValueAsString(report);
        if (Files.exists(reportPath)) {
            if (!verifyReport(reportPath).equals(report)) throw new IOException("A3 Fixture report conflicts with replay");
            return false;
        }
        Files.createDirectories(reportPath.getParent());
        String content = payload + "\n" + FOOTER + sha256(payload) + "\n";
        Path temp = reportPath.resolveSibling("." + reportPath.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        try {
            Files.move(temp, reportPath, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException race) {
            Files.deleteIfExists(temp);
            if (!verifyReport(reportPath).equals(report)) throw new IOException("A3 Fixture report conflicts with replay");
            return false;
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    private static String readVerified(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("A3 Fixture evaluation report is missing");
        String[] lines = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[1].startsWith(FOOTER) || !lines[2].isEmpty()
            || !lines[1].substring(FOOTER.length()).equals(sha256(lines[0]))) {
            throw new IOException("A3 Fixture evaluation report checksum is invalid");
        }
        return lines[0];
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for Fixture Shadow evaluation", e);
        }
    }

    private enum CaseKind { ELIGIBLE, RECOVERED, STALE, WRONG_TARGET, WRONG_ACTION, DB_LOCK_WAIT, INSUFFICIENT, MODEL_OPPOSES }
}
