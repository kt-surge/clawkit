package com.clawkit.evaluation.context;

import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class ContextMemoryReplayTest {
    @TempDir Path temp;

    @Test void runsEightRiskFamiliesAndCanRecomputeWithoutScores() throws Exception {
        Path output = temp.resolve("run");
        String originalHome = System.getProperty("user.home");
        var report = ContextMemoryReplay.run(output);
        assertThat(report.artifactIntegrity()).isTrue();
        assertThat(report.attemptedCases()).isEqualTo(10);
        assertThat(report.cases().stream().map(ReplayAudit.CaseResult::family).distinct()).hasSize(8);
        assertThat(report.cases()).allSatisfy(result -> {
            assertThat(result.executionFailure()).as(result.id()).isNull();
            assertThat(result.checks()).as(result.id()).allMatch(ReplayAudit.CheckResult::passed);
            assertThat(result.usage().actualTotalTokens()).isNull();
        });
        assertThat(report.mechanismPassed()).isEqualTo(10);
        assertThat(System.getProperty("user.home")).isEqualTo(originalHome);
        var audit = ReplayAudit.recompute(output);
        assertThat(audit).isEqualTo(report);
        assertThat(audit.effectClaims()).contains("NO_LIVE");
        // The unknown case only gets its own garden memory, never order memory from another case.
        assertThat(Files.readString(output.resolve("instances/unknown-query/observation.json"))).doesNotContain("retry twice");
        assertThat(Files.readString(output.resolve("agents/unknown-query/home/.clawkit/memory/MEMORY.md")))
            .contains("garden").doesNotContain("order-policy");
        assertThatThrownBy(() -> ContextMemoryReplay.run(output)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    @Test void multiSourceRecallIsNotAnyHitAndDeduplicatesSources() {
        var partial = EvidenceMetrics.score(List.of("a", "a", "unrelated"), 5, List.of(Set.of("a", "b")));
        assertThat(partial.sourceRecall()).isEqualTo(.5);
        assertThat(partial.anyEvidenceHit()).isTrue();
        assertThat(partial.allEvidenceHit()).isFalse();
        assertThat(partial.precision()).isEqualTo(.5);
        var alternative = EvidenceMetrics.score(List.of("replacement"), 5, List.of(Set.of("a", "b"), Set.of("replacement")));
        assertThat(alternative.allEvidenceHit()).isTrue();
        assertThat(alternative.sourceRecall()).isEqualTo(1);
        var unknown = EvidenceMetrics.score(List.of(), 5, List.of());
        assertThat(unknown.unanswerable()).isTrue();
        assertThat(unknown.sourceRecall()).isNull();
        assertThat(unknown.precision()).isNull();
    }

    @Test void countsAllPhasesAndFailuresWithoutCountingCacheAndReasoningTwice() {
        var ledger = new UsageLedger();
        var actual = new TokenUsage(100, 30, 130, 60, 40, 10, UsageSource.ACTUAL);
        ledger.add(new UsageLedger.Entry("ingest", "MEMORY_EXTRACT", "COMPLETED", 10, actual, null));
        ledger.add(new UsageLedger.Entry("compact", "COMPACT", "FAILED", 20, TokenUsage.EMPTY, "Timeout"));
        var totals = ledger.totals();
        assertThat(totals.calls()).isEqualTo(2);
        assertThat(totals.failedCalls()).isEqualTo(1);
        assertThat(totals.actualTotalTokens()).isEqualTo(130);
        assertThat(totals.cacheHitInputTokens()).isEqualTo(60);
        assertThat(totals.reasoningOutputTokens()).isEqualTo(10);
        assertThat(totals.unavailableCalls()).isEqualTo(1);
        assertThat(totals.completeActualUsage()).isFalse();
        assertThatThrownBy(() -> ledger.add(ledger.entries().getFirst())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void capturesFailedCallsAndKeepsRawRequestForIndependentAudit() throws Exception {
        var artifacts = new EvaluationArtifacts(temp.resolve("failure"));
        var ledger = new UsageLedger();
        ProviderGateway failing = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest r, RunScope s) { throw new LLMException("offline failure"); }
            @Override public ModelResponse generateStream(ModelRequest r, RunScope s, StreamObserver o) { return generate(r, s); }
        };
        var gateway = new EvaluationGateway(failing, artifacts, ledger, "instance");
        assertThatThrownBy(() -> gateway.generate(ModelRequest.of(List.of(Message.user("only public fixture")), List.of()),
            new RunScope("run", null, 1, RunPhase.REACT, ExecutionMode.REACT))).isInstanceOf(LLMException.class);
        assertThat(ledger.totals().actualTotalTokens()).isNull();
        assertThat(ledger.totals().failedCalls()).isEqualTo(1);
        assertThat(Files.readString(artifacts.resolve("instance/calls/instance-call-1-request.json")))
            .contains("only public fixture").doesNotContain("expected", "gold");
        assertThat(Files.readString(artifacts.resolve("instance/calls/instance-call-1-response.json"))).contains("FAILED", "UNAVAILABLE");
    }

    @Test void goldAndScriptsAreOutsideAgentInputsAndArtifactMutationIsDetected() throws Exception {
        Path output = temp.resolve("audit");
        ContextMemoryReplay.run(output);
        var input = EvaluationArtifacts.JSON.readTree(output.resolve("instances/multi-source/agent-input.json").toFile());
        assertThat(input.has("gold")).isFalse();
        assertThat(input.has("script")).isFalse();
        try (var requests = Files.walk(output.resolve("instances"))) {
            for (Path path : requests.filter(p -> p.toString().endsWith("-request.json")).toList()) {
                assertThat(Files.readString(path)).doesNotContain("evidenceAlternatives", "mechanismPassed", "expected\"");
            }
        }
        Path observation = output.resolve("instances/unknown-query/observation.json");
        var raw = EvaluationArtifacts.JSON.readTree(observation.toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("executionFailure", "SimulatedTimeout");
        Files.write(observation, EvaluationArtifacts.JSON.writeValueAsBytes(raw));
        var changed = ReplayAudit.recompute(output);
        assertThat(changed.artifactIntegrity()).isFalse();
        assertThat(changed.mechanismPassed()).isLessThan(changed.attemptedCases());
        assertThat(changed.cases()).filteredOn(c -> c.id().equals("unknown-query")).singleElement()
            .satisfies(c -> assertThat(c.executionFailure()).isEqualTo("SimulatedTimeout"));
    }
}
