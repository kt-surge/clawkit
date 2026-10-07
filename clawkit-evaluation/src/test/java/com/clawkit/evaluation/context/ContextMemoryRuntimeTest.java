package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.engine.impl.FileSessionStore;
import com.clawkit.engine.impl.ObservingProviderGateway;
import com.clawkit.evaluation.ScriptedProvider;
import com.clawkit.evaluation.ScriptedStep;
import com.clawkit.evaluation.scorer.FileStateScorer;
import com.clawkit.evaluation.scorer.ScoreStatus;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.provider.*;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.impl.ReadTool;
import com.clawkit.tools.impl.WriteTool;
import com.clawkit.tools.schema.Message;
import com.clawkit.tools.schema.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.clawkit.evaluation.context.EvaluationArtifacts.JSON;
import static org.assertj.core.api.Assertions.*;

class ContextMemoryRuntimeTest {
    @TempDir Path temp;

    @Test void actualEngineLoadsProgressReadsFreshEvidenceAndProducesIndependentlyCheckedFile() throws Exception {
        var artifacts = new EvaluationArtifacts(temp.resolve("experiment"));
        Path home = artifacts.resolve("agent/home");
        Path work = artifacts.resolve("agent/workspace");
        Files.createDirectories(work.resolve(".clawkit"));
        Files.writeString(work.resolve(".clawkit/todo.md"), "- [x] schema complete\n- [ ] write report\n");
        Files.writeString(work.resolve("health.json"), "{\"healthy\":false}");
        var provider = new ScriptedProvider(List.of(
            ScriptedStep.toolCall(Message.assistantWithTools(List.of(new ToolCall("read-current", "read", JSON.createObjectNode().put("path", "health.json"))))),
            ScriptedStep.toolCall(Message.assistantWithTools(List.of(new ToolCall("write-report", "write", JSON.createObjectNode()
                .put("path", "report.json").put("content", "{\"healthy\":false,\"schemaComplete\":true}"))))),
            ScriptedStep.text("independent write verification complete"),
            ScriptedStep.text("report written")));
        var ledger = new UsageLedger();
        try (var recorder = new FileRunRecorder(home.resolve(".clawkit"))) {
            var gateway = new EvaluationGateway(new ObservingProviderGateway(provider, recorder), artifacts, ledger, "instance");
            var registry = new ToolRegistry();
            registry.register(new ReadTool(work));
            // This fixture deliberately requires WORKFLOW verification, retaining cross-root cost coverage.
            registry.register(new WriteTool(work) {
                @Override public com.clawkit.tools.action.ActionDescriptor describeAction(com.clawkit.tools.ToolExecutionRequest request) {
                    var descriptor = super.describeAction(request);
                    if (descriptor == null) return null;
                    return new com.clawkit.tools.action.ActionDescriptor(descriptor.actionCode(), descriptor.canonicalTarget(),
                        descriptor.parameterDigest(), descriptor.riskLevel(), descriptor.reversibility(), descriptor.reliability(),
                        com.clawkit.tools.action.VerificationMode.WORKFLOW, descriptor.preconditions(), descriptor.expectedEffects(),
                        descriptor.compensationSummary(), descriptor.blastRadius());
                }
            });
            var tokenizer = new CharFallbackTokenizer();
            var budget = ContextBudgetPolicy.of(128_000);
            var pipeline = new RecordingContextPipeline(new DefaultContextPipeline(new LadderedCompactor(null, tokenizer),
                new ContextBudgetAnalyzer(tokenizer, budget), tokenizer, budget), artifacts, "instance");
            var engine = new AgentEngine(new AgentRuntimeDependencies(gateway, pipeline, registry, 128_000, "cl100k_base",
                recorder, AgentRuntimeDependencies.noopMemoryHooks(), AgentRuntimeDependencies.emptySkillRuntime()), work.toString(), ThinkingMode.OFF, "");
            var store = new FileSessionStore(home.resolve(".clawkit/sessions"));
            var now = Instant.parse("2026-10-01T00:00:00Z");
            store.save(new SessionDocument(1, "history", "progress", now, now,
                List.of(Message.user("Only create report.json; schema is already complete."), Message.assistant("Recorded.")), Map.of()));
            engine.setSessionService(new SessionService(store));
            engine.loadSession("history");
            engine.setRunLimits(java.time.Duration.ofSeconds(10), 30_000L, 8L, 4L);
            engine.run("Continue with the remaining report; read health.json first.");
            var score = FileStateScorer.exact(Map.of("report.json", "{\"healthy\":false,\"schemaComplete\":true}"))
                .score(null, null, work);
            assertThat(score.status()).isEqualTo(ScoreStatus.PASS);
            assertThat(provider.allStepsConsumed()).isTrue();
            assertThat(ledger.totals().calls()).isEqualTo(4); // Includes the independent verification run.
            assertThat(ledger.totals().failedCalls()).isZero();
            assertThat(ledger.totals().actualTotalTokens()).isNull();
            String build = Files.readString(artifacts.resolve("instance/context/build-1.json"));
            assertThat(build).contains("WORKSPACE", "schema complete", "SESSION", "Only create report.json");
            assertThat(Files.readString(artifacts.resolve("instance/calls/instance-call-2-request.json")))
                .contains("healthy\\\":false").doesNotContain("gold", "expectedOutput");
        }
    }

    @Test void paidRejectedResponsesStayInUsageAndFailureEvidence() throws Exception {
        var artifacts = new EvaluationArtifacts(temp.resolve("rejected"));
        var ledger = new UsageLedger();
        var usage = new TokenUsage(70, 20, 90);
        ProviderGateway rejected = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest r, RunScope s) {
                throw new LLMException("invalid response", null, null, 0,
                    RejectedModelResponse.bounded("parse", "untrusted received JSON", usage));
            }
            @Override public ModelResponse generateStream(ModelRequest r, RunScope s, StreamObserver o) { return generate(r, s); }
        };
        var gateway = new EvaluationGateway(rejected, artifacts, ledger, "instance");
        assertThatThrownBy(() -> gateway.generate(ModelRequest.of(List.of(Message.user("public")), List.of()),
            new RunScope("run", null, 1, RunPhase.REACT, ExecutionMode.REACT))).isInstanceOf(LLMException.class);
        assertThat(ledger.totals().actualTotalTokens()).isEqualTo(90);
        assertThat(ledger.totals().failedCalls()).isEqualTo(1);
        assertThat(Files.readString(artifacts.resolve("instance/calls/instance-call-1-response.json")))
            .contains("untrusted received JSON", "rejectedResponse");
    }
}
