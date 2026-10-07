package com.clawkit.evaluation.context;

import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class ContinuationInstanceTest {
    @TempDir Path temp;
    @Test void nativeMemoryIngestionReopensStoresAndIncludesAllCostsInTheSameCap() throws Exception {
        var spec = ContinuationSpec.parse(EvaluationArtifacts.JSON.readTree(Files.readAllBytes(
            EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-dev-v1.json"))));
        var instance = spec.instances().stream().filter(i -> i.taskId().equals("combined-memory")
            && i.arm() == ContinuationSpec.Arm.M2_CURRENT_MEMORY_AND_SESSION_BM25).findFirst().orElseThrow();
        var calls = new AtomicInteger();
        var requests = new java.util.ArrayList<ModelRequest>();
        LLMProvider fixture = new LLMProvider() {
            @Override public ModelResponse generate(ModelRequest request) {
                requests.add(request);
                int call = calls.incrementAndGet();
                String text = switch (call) {
                    case 1 -> "[{\"name\":\"order-notify\",\"description\":\"订单项目通知约定\",\"type\":\"project\",\"content\":\"通知渠道 ops-inbox，等级 critical。\"}]";
                    case 2 -> "订单项目通知渠道 ops-inbox，等级 critical。";
                    case 3 -> "[{\"name\":\"order-repair\",\"description\":\"订单项目自动处理约定\",\"type\":\"project\",\"content\":\"自动处理仅 staging 环境，动作为 restart。\"}]";
                    case 4 -> "订单项目自动处理仅 staging 环境，动作为 restart。";
                    case 6 -> "completed";
                    case 5 -> null;
                    default -> throw new AssertionError("unexpected extra fixture call");
                };
                if (call == 5) return new ModelResponse(null, List.of(new ToolCall("policy-write", "write",
                    EvaluationArtifacts.JSON.createObjectNode().put("path", "policy.json").put("content",
                        "{\"notificationChannel\":\"ops-inbox\",\"notificationSeverity\":\"critical\",\"automaticEnvironment\":\"staging\",\"automaticAction\":\"restart\"}"))),
                    FinishReason.TOOL_CALLS, new TokenUsage(10, 2, 12), ProviderResponseMetadata.EMPTY);
                return ModelResponse.text(text, new TokenUsage(10, 2, 12));
            }
            @Override public Message generate(List<Message> messages, List<ToolDefinition> tools) { throw new AssertionError("V2 expected"); }
        };
        var artifacts = new EvaluationArtifacts(temp.resolve("native-ingestion-fixture-only"));
        var pool = BudgetLedger.of(spec.limits().totalTokens());
        var row = ContinuationInstance.execute(spec, instance, artifacts, fixture, pool, WorkBudgetLedger.of(144, Long.MAX_VALUE));
        var failureFile = artifacts.resolve("instances/" + instance.id() + "/evidence-failure.json");
        var runtimeFile = artifacts.resolve("instances/" + instance.id() + "/runtime-failure.json");
        assertThat(row.status()).as("row=%s; evidence=%s; runtime=%s", row,
            Files.exists(failureFile) ? Files.readString(failureFile) : "none",
            Files.exists(runtimeFile) ? Files.readString(runtimeFile) : "none").isEqualTo("PASS");
        assertThat(row.dispatchedProviderCalls()).isEqualTo(6);
        assertThat(row.usage().actualTotalTokens()).isEqualTo(72);
        assertThat(pool.remaining()).isEqualTo(spec.limits().totalTokens() - 72);
        String visible = requests.get(4).messages().stream().map(Message::content).filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(visible).contains("ops-inbox", "critical", "staging", "restart");
        assertThat(Files.readString(artifacts.resolve("instances/" + instance.id() + "/ingestion/provenance.json")))
            .contains("notify-discussion", "repair-discussion", "DERIVATION_ONLY_NOT_FACT_VERIFICATION");
        sealRun(spec, instance, artifacts, row);
        var audit = ContinuationAudit.recompute(artifacts.root());
        assertThat(audit.outcomesAgree()).as("native ingestion audit: %s", audit).isTrue();
        assertThat(audit.passed()).isEqualTo(1);
        assertThat(audit.recomputed().stream().filter(r -> r.get("id").equals(instance.id())).findFirst().orElseThrow())
            .containsEntry("dispatchedProviderCalls", 6L);
    }

    @Test void actualEngineAndSavedTraceAccountForTaskAndDeterministicVerification() throws Exception {
        var spec = ContinuationSpec.parse(EvaluationArtifacts.JSON.readTree(Files.readAllBytes(
            EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-dev-v1.json"))));
        var instance = spec.instances().stream().filter(i -> i.taskId().equals("unconfirmed-memory") && i.arm() == ContinuationSpec.Arm.M0_NO_HISTORY)
            .findFirst().orElseThrow();
        var calls = new AtomicInteger();
        // Synthetic usage values exercise accounting only. This provider makes zero network calls.
        LLMProvider fixture = new LLMProvider() {
            @Override public ModelResponse generate(ModelRequest request) {
                int call = calls.incrementAndGet();
                if (call == 1) return new ModelResponse(null, List.of(new ToolCall("region-write", "write",
                    EvaluationArtifacts.JSON.createObjectNode().put("path", "region.json").put("content", "{\"deploymentRegion\":null,\"confirmed\":false}"))),
                    FinishReason.TOOL_CALLS, new TokenUsage(10, 2, 12), ProviderResponseMetadata.EMPTY);
                if (call > 2) throw new AssertionError("unexpected extra model call in fixture");
                return ModelResponse.text("completed", new TokenUsage(10, 2, 12));
            }
            @Override public Message generate(List<Message> messages, List<ToolDefinition> tools) { throw new AssertionError("V2 provider expected"); }
        };
        var artifacts = new EvaluationArtifacts(temp.resolve("fixture-only"));
        var pool = BudgetLedger.of(spec.limits().totalTokens());
        var row = ContinuationInstance.execute(spec, instance, artifacts, fixture, pool, WorkBudgetLedger.of(144, Long.MAX_VALUE));
        assertThat(row.status()).isEqualTo("PASS");
        assertThat(row.outcome().taskCompleted()).isTrue();
        assertThat(row.dispatchedProviderCalls()).isEqualTo(2);
        assertThat(row.accountingInvalid()).isFalse();
        assertThat(pool.remaining()).isEqualTo(spec.limits().totalTokens() - 24);
        assertThat(Files.readString(artifacts.resolve("instances/" + instance.id() + "/executed-tools.json")))
            .contains("region-write", "region.json", "write");

        // Regrade actual saved events and provider exchanges; do not trust the runner's PASS.
        sealRun(spec, instance, artifacts, row);
        var audit = ContinuationAudit.recompute(artifacts.root());
        assertThat(audit.artifactIntegrity()).isTrue();
        assertThat(audit.outcomesAgree()).as("independent audit: %s", audit).isTrue();
        assertThat(audit.planned()).isEqualTo(18);
        assertThat(audit.attempted()).isEqualTo(1);
        assertThat(audit.passed()).isEqualTo(1);
        assertThat(audit.notRun()).isEqualTo(17);

        Files.writeString(artifacts.resolve("agents/" + instance.id() + "/workspace/region.json"),
            "{\"deploymentRegion\":\"unsupported\",\"confirmed\":true}");
        var tampered = ContinuationAudit.recompute(artifacts.root());
        assertThat(tampered.artifactIntegrity()).isFalse();
        assertThat(tampered.outcomesAgree()).isFalse();
        assertThat(tampered.passed()).isZero();
    }


    @Test void compactionContinuationFindsRootEvidenceAndFinishesWithoutRewritingCompletedWork() throws Exception {
        var spec = ContinuationSpec.parse(EvaluationArtifacts.JSON.readTree(Files.readAllBytes(
            EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-dev-v1.json"))));
        var instance = spec.instances().stream().filter(i -> i.taskId().equals("resume-verification")
            && i.arm() == ContinuationSpec.Arm.C2_CURRENT_LADDER_GENERAL).findFirst().orElseThrow();
        var calls = new AtomicInteger();
        var summaryCalls = new AtomicInteger();
        LLMProvider fixture = new LLMProvider() {
            @Override public ModelResponse generate(ModelRequest request) {
                if (request.tools().isEmpty()) {
                    summaryCalls.incrementAndGet();
                    return ModelResponse.text(
                    "schema.sql已完成并验收，禁止再修改；剩余任务是实际读取current-health.json，并将healthy和schemaComplete=true写入acceptance.json。",
                    new TokenUsage(10, 2, 12));
                }
                int call = calls.incrementAndGet();
                ToolCall tool = switch (call) {
                    case 1 -> new ToolCall("find-current-health", "glob", EvaluationArtifacts.JSON.createObjectNode().put("pattern", "**/current-health.json"));
                    case 2 -> {
                        assertThat(request.messages().stream().filter(m -> m.role() == Role.TOOL).map(Message::content))
                            .anyMatch(text -> text != null && text.contains("  current-health.json\n"));
                        yield new ToolCall("read-current-health", "read", EvaluationArtifacts.JSON.createObjectNode().put("path", "current-health.json"));
                    }
                    case 3 -> {
                        assertThat(request.messages().stream().filter(m -> m.role() == Role.TOOL).map(Message::content))
                            .anyMatch(text -> text != null && text.contains("\"healthy\":false"));
                        yield new ToolCall("write-acceptance", "write", EvaluationArtifacts.JSON.createObjectNode()
                            .put("path", "acceptance.json").put("content", "{\"healthy\":false,\"schemaComplete\":true}"));
                    }
                    case 4 -> null;
                    default -> throw new AssertionError("unexpected extra fixture model call");
                };
                if (tool == null) return ModelResponse.text("completed", new TokenUsage(10, 2, 12));
                return new ModelResponse(null, List.of(tool), FinishReason.TOOL_CALLS,
                    new TokenUsage(10, 2, 12), ProviderResponseMetadata.EMPTY);
            }
            @Override public Message generate(List<Message> messages, List<ToolDefinition> tools) { throw new AssertionError("V2 expected"); }
        };
        var artifacts = new EvaluationArtifacts(temp.resolve("root-evidence-continuation-fixture-only"));
        var pool = BudgetLedger.of(spec.limits().totalTokens());
        var row = ContinuationInstance.execute(spec, instance, artifacts, fixture, pool, WorkBudgetLedger.of(144, Long.MAX_VALUE));
        assertThat(row.status()).as("root evidence continuation %s", row).isEqualTo("PASS");
        assertThat(row.outcome().requiredReadsPresent()).isTrue();
        assertThat(row.outcome().constraintsObeyed()).isTrue();
        assertThat(calls).hasValue(4);
        assertThat(summaryCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(row.dispatchedProviderCalls()).isEqualTo(4 + summaryCalls.get());
        assertThat(row.usage().actualTotalTokens()).isEqualTo(12 * (4 + summaryCalls.get()));
        sealRun(spec, instance, artifacts, row);
        var audit = ContinuationAudit.recompute(artifacts.root());
        assertThat(audit.artifactIntegrity()).isTrue();
        assertThat(audit.outcomesAgree()).isTrue();
        assertThat(audit.passed()).isEqualTo(1);
    }

    private static void sealRun(ContinuationSpec spec, ContinuationSpec.Instance instance,
                                EvaluationArtifacts artifacts, ContinuationInstance.Row row) throws Exception {
        artifacts.write("frozen-data.json", EvaluationArtifacts.JSON.readTree(Files.readAllBytes(
            EvaluationSourceSnapshot.repository().resolve("benchmarks/context-memory-dev-v1.json"))));
        var sources = EvaluationSourceSnapshot.freeze(artifacts);
        artifacts.write("manifest.json", Map.of("datasetHash", EvaluationArtifacts.sha256(
            Files.readAllBytes(artifacts.resolve("frozen-data.json"))), "sourceHashes", sources));
        artifacts.write("source-integrity.json", Map.of("unchanged", true));
        for (var planned : spec.instances()) {
            artifacts.append("instances.jsonl", planned.id().equals(instance.id()) ? row :
                new ContinuationInstance.Row(planned.id(), planned.taskId(), planned.suite().name(), planned.arm().name(),
                    "NOT_RUN", "FIXTURE_ONLY", null, null, 0, 0, false));
        }
        artifacts.seal();
    }
}
