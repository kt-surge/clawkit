package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.ops.loop.repair.FixSession;
import com.clawkit.ops.loop.repair.RepairResult;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates reproducible A2 evidence with disposable in-process Fixtures.
 *
 * <p>This is deliberately not a repair launcher: its read sessions and fix
 * session never open SSH, and the report makes the simulated approval and
 * fixture-only call count explicit.  It exists to make the A2 success and
 * outcome-unknown boundaries inspectable outside of a JUnit report.
 */
public final class FixtureAskEvidenceMain {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneId.of("UTC"));
    static final String REPORT_FILE = "a2-fixture-evidence-report.json";

    private FixtureAskEvidenceMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: FixtureAskEvidenceMain <empty-output-directory>");
        }
        Path report = run(Path.of(args[0]));
        System.out.println("A2 Fixture evidence: " + report.toAbsolutePath());
        System.out.println("Remote writes: 0 (all fix calls are in-process Fixture doubles)");
    }

    static Path run(Path outputDirectory) throws Exception {
        Files.createDirectories(outputDirectory);
        try (var entries = Files.list(outputDirectory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("output directory must be empty: " + outputDirectory);
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("mode", "FIXTURE_A2_APPROVAL_CONTRACT");
        report.put("executedAt", CLOCK.instant().toString());
        report.put("fixtureOnly", true);
        report.put("remoteWrites", 0);
        report.put("scenarios", List.of(
            exercise(outputDirectory.resolve("approve-verified"), Scenario.APPROVE_VERIFIED),
            exercise(outputDirectory.resolve("outcome-unknown"), Scenario.OUTCOME_UNKNOWN)
        ));
        Path reportFile = outputDirectory.resolve(REPORT_FILE);
        writeWithChecksum(reportFile, MAPPER.writeValueAsString(report));
        return reportFile;
    }

    private static Map<String, Object> exercise(Path home, Scenario scenario) {
        Counters counters = new Counters();
        OpsInvestigationFacade facade = facade(home, scenario, counters);
        try {
            InvestigationView view = facade.investigateAndMaybeRepair(
                new InvestigationRequest("fixture", "order-api", null), new AutoApprove(counters));
            if (scenario == Scenario.APPROVE_VERIFIED && view.status() != UserIncidentStatus.RESOLVED) {
                throw new IllegalStateException("Fixture approval did not independently verify: "
                    + view.status() + " / " + view.summary() + " / " + view.verificationSummary());
            }
            if (scenario == Scenario.OUTCOME_UNKNOWN && view.status() != UserIncidentStatus.NEEDS_HUMAN) {
                throw new IllegalStateException("Fixture unknown outcome did not require human handling: "
                    + view.status() + " / " + view.summary());
            }
            int callsBeforeContinue = counters.fixtureFixCalls.get();
            if (scenario == Scenario.OUTCOME_UNKNOWN) {
                facade.continueIncident(view.incidentId(), new AutoApprove(new Counters()));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", scenario.name());
            result.put("simulatedApproval", true);
            result.put("finalStatus", view.status().name());
            result.put("approvalPrompts", counters.approvalPrompts.get());
            result.put("fixtureFixCalls", counters.fixtureFixCalls.get());
            result.put("remoteWrites", 0);
            result.put("independentVerification", counters.verifySessions.get() > 0);
            result.put("continueFixCallDelta", counters.fixtureFixCalls.get() - callsBeforeContinue);
            result.put("outcomeUnknown", scenario == Scenario.OUTCOME_UNKNOWN);
            return result;
        } finally {
            facade.close();
        }
    }

    private static OpsInvestigationFacade facade(Path home, Scenario scenario, Counters counters) {
        OpsInvestigationFacade.InitialReadSessionProvider borrow = target -> new ReadSession(target, State.APP_DOWN);
        AtomicInteger freshCalls = new AtomicInteger();
        OpsInvestigationFacade.FreshReadSessionFactory fresh = target -> {
            int call = freshCalls.incrementAndGet();
            if (call > 2) counters.verifySessions.incrementAndGet();
            State state = scenario == Scenario.APPROVE_VERIFIED && call > 2 ? State.RUNNING : State.APP_DOWN;
            return new ReadSession(target, state);
        };
        OpsInvestigationFacade.FixSessionFactory fix = target -> new FixtureFixSession(scenario, counters);
        return new OpsInvestigationFacade(home, borrow, fresh, fix,
            ignored -> { throw new UnsupportedOperationException("Fixture does not call a model"); }, CLOCK);
    }

    private static void writeWithChecksum(Path file, String payload) throws Exception {
        String content = payload + "\n# SHA-256: " + sha256(payload) + "\n";
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String sha256(String text) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private enum Scenario { APPROVE_VERIFIED, OUTCOME_UNKNOWN }
    private enum State { APP_DOWN, RUNNING }

    private static final class Counters {
        private final AtomicInteger approvalPrompts = new AtomicInteger();
        private final AtomicInteger fixtureFixCalls = new AtomicInteger();
        private final AtomicInteger verifySessions = new AtomicInteger();
    }

    private record AutoApprove(Counters counters) implements InvestigationInteraction {
        @Override public void onProgress(InvestigationProgress progress) { }
        @Override public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
            counters.approvalPrompts.incrementAndGet();
            return ApprovalDecision.APPROVE;
        }
        @Override public void onFinalResult(InvestigationView result) { }
    }

    private static final class FixtureFixSession implements FixSession {
        private final Scenario scenario;
        private final Counters counters;
        private FixtureFixSession(Scenario scenario, Counters counters) {
            this.scenario = scenario;
            this.counters = counters;
        }
        @Override public RepairResult executeRestart(String incidentId, String repairRunId) throws IOException {
            counters.fixtureFixCalls.incrementAndGet();
            if (scenario == Scenario.OUTCOME_UNKNOWN) throw new IOException("Fixture transport interrupted after dispatch");
            Instant now = CLOCK.instant();
            return new RepairResult(incidentId, "fixture-" + repairRunId, repairRunId,
                AttemptState.VERIFICATION_PENDING, EffectCertainty.EFFECT_CONFIRMED, FailureClass.NONE,
                "Fixture restart reported success", now.minusSeconds(1), now, null);
        }
        @Override public void close() { }
    }

    private record ReadSession(String targetId, State state) implements OpsReadSession {
        @Override public McpCallResult callTool(String tool, ObjectNode arguments) {
            String stateValue = state == State.APP_DOWN ? "exited" : "running";
            int http = state == State.APP_DOWN ? 503 : 200;
            String response = switch (tool) {
                case "service_status", "container_status" -> "{\"success\":true,\"data\":{\"State\":\"" + stateValue + "\"}}";
                case "http_probe" -> "{\"success\":true,\"data\":{\"statusCode\":" + http + "}}";
                case "ports" -> "{\"success\":true,\"data\":{\"bindings\":[{\"PublishedPort\":8080}]}}";
                case "logs" -> "{\"success\":true,\"data\":{\"text\":\"fixture only\"}}";
                default -> "{\"success\":true}";
            };
            return McpCallResult.success(response, List.of());
        }
        @Override public boolean isReady() { return true; }
        @Override public void close() { }
    }
}
