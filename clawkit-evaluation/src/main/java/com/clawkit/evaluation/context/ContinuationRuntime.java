package com.clawkit.evaluation.context;

import com.clawkit.engine.*;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.engine.impl.FileSessionStore;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.tools.impl.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Shared runtime assembly: preflight and live continuation use the same local task environment. */
final class ContinuationRuntime {
    static final String RULES = """
        当前工作目录是隔离的测试项目，只使用本次实际提供的文件工具。
        依据用户确认的事实完成任务；未确认的信息用 null，不猜测。
        需要历史约定时，可查找工作目录中的 archive。没有历史证据时仍须按用户给定的格式输出。
        文件路径均相对当前工作目录；不访问外部服务。只写用户约定的结果文件，不创建其他文件。
        写入完成后结束任务。
        """;
    private ContinuationRuntime() {}
    static ToolRegistry tools(Path workspace) {
        var registry = new ToolRegistry();
        registry.register(new ReadTool(workspace));
        registry.register(new WriteTool(workspace));
        registry.register(new GlobTool(workspace));
        registry.register(new GrepTool(workspace));
        return registry;
    }
    static void seed(Path workspace, Map<String, String> files) throws Exception {
        Files.createDirectories(workspace);
        for (var file : files.entrySet()) {
            ContinuationSpec.relativeFile(file.getKey());
            Path target = workspace.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardOpenOption.CREATE_NEW);
        }
    }
    static void loadRawHistory(AgentEngine engine, Path home, ReplayCase.HistorySource source) {
        var store = new FileSessionStore(home.resolve(".clawkit/sessions"));
        store.save(new SessionDocument(1, source.id(), source.id(), source.observedAt(), source.observedAt(), source.messages(), Map.of()));
        engine.setSessionService(new SessionService(store));
        engine.loadSession(source.id());
        engine.setSessionService(null); // Same saved history; no cross-session summary retrieval in this suite.
    }
    static void configure(AgentEngine engine, ContinuationSpec spec) {
        engine.setWorkspaceRules(RULES);
        engine.setRunLimits(java.time.Duration.ofSeconds(spec.settings().instanceDeadlineSeconds()), spec.limits().instanceTotalTokens(),
            (long) spec.limits().instanceProviderCalls(), (long) spec.settings().instanceToolCalls());
    }
}
