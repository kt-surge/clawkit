package com.clawkit.engine.impl;

import com.clawkit.engine.*;
import com.clawkit.observability.*;
import com.clawkit.provider.*;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.impl.ReadTool;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Real engine and file operations; predetermined responses are mechanism evidence, never live-full usage. */
class SingleRequestContextTest {
    @TempDir Path workspace;
    @Test void ordinaryPipelineFinishesTwentyFourToolExchangesWithoutReplacingTheCurrentUserRequest() throws Exception {
        String task = "完成配置检查；仅查看本地样本，禁止执行删除、重启或生产变更。";
        for (int i = 1; i <= 24; i++) {
            var sample = new StringBuilder("sample,count,latency,queue,ready\n");
            for (int row = 0; row < 55; row++) sample.append(i).append(',').append(200 + row).append(',')
                .append(54 + i + row).append(',').append(1 + row).append(",false\n");
            Files.writeString(workspace.resolve("sample-" + i + ".csv"), sample.toString());
        }
        var main = new AtomicInteger(); var summaries = new AtomicInteger(); var mapper = new ObjectMapper();
        ProviderGateway gateway = new ProviderGateway() {
            @Override public ModelResponse generate(ModelRequest request, RunScope scope) {
                if (scope.phase() == RunPhase.COMPACT) { summaries.incrementAndGet(); return ModelResponse.text("已检查前面的样本；继续剩余检查，生产变更仍被禁止。", TokenUsage.EMPTY); }
                assertThat(scope.phase()).isEqualTo(RunPhase.REACT);
                assertThat(request.messages()).anyMatch(message -> message.role() == Role.USER && task.equals(message.content()));
                int count = main.incrementAndGet();
                if (count > 24) return ModelResponse.text("样本检查完成。", TokenUsage.EMPTY);
                return new ModelResponse("检查下一个样本。", List.of(new ToolCall("sample-" + count, "read",
                    mapper.createObjectNode().put("path", "sample-" + count + ".csv"))), FinishReason.TOOL_CALLS, TokenUsage.EMPTY, ProviderResponseMetadata.EMPTY);
            }
            @Override public ModelResponse generateStream(ModelRequest request, RunScope scope, StreamObserver observer) { return generate(request, scope); }
        };
        var registry = new ToolRegistry(); registry.register(new ReadTool(workspace)); var events = new ArrayList<RunEventPayload>();
        RunRecorder recorder = (payload, runId, parentRunId, turn, time) -> events.add(payload);
        var engine = new AgentEngine(new AgentRuntimeDependencies(gateway, null, registry, 4096, "cl100k_base", recorder,
            AgentRuntimeDependencies.noopMemoryHooks(), AgentRuntimeDependencies.emptySkillRuntime()), workspace.toString(), ThinkingMode.OFF, "");
        assertThat(engine.run(task)).isEqualTo("样本检查完成。");
        assertThat(main).hasValue(25); assertThat(summaries).hasPositiveValue();
        assertThat(events.stream().filter(TurnStartedPayload.class::isInstance)).hasSize(25);
        assertThat(events.stream().filter(CompactCompletedPayload.class::isInstance).map(CompactCompletedPayload.class::cast))
            .allSatisfy(compact -> assertThat(compact.failed()).isFalse());
        assertThat(events.stream().filter(RunCompletedPayload.class::isInstance).map(RunCompletedPayload.class::cast))
            .singleElement().satisfies(completed -> assertThat(completed.status()).isEqualTo(RunStatus.COMPLETED));
    }
}
