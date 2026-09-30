package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.*;
import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * APP_DOWN 审批拒绝夹具测试。
 *
 * <p>模拟远端 order-api 已停止（service_status→exited,
 * container_status→exited, http_probe→connection refused），
 * 验证拒绝和取消两条路径：
 * <ol>
 *   <li>写操作计数器为零（fix session 从未创建，restart_service 从未调用）</li>
 *   <li>InvestigationView 内容正确（中文终态、零副作用保证）</li>
 *   <li>/ops inspect 持久化状态为 REJECTED / CANCELLED</li>
 * </ol>
 *
 * <p>这是 PRODUCT-3 审批决策体验的最小闭环验收。
 */
class AppDownRejectFixtureTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-03T12:00:00Z"), ZoneId.of("UTC"));

    @TempDir Path homeDir;
    private final List<OpsInvestigationFacade> facades = new ArrayList<>();

    @AfterEach
    void tearDown() {
        facades.forEach(OpsInvestigationFacade::close);
    }

    // ── 场景 1：拒绝（reject）─────────────────────────────────────────

    @Test
    void rejectShowsZeroSideEffectTerminal() {
        var counters = new WriteCounters();
        var facade = createFacade(counters);
        var interaction = new CapturingInteraction(ApprovalDecision.REJECT, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        // 写操作计数器必须为零
        assertThat(counters.fixSessionCreated.get())
            .as("write session count")
            .isEqualTo(0);
        assertThat(counters.approvalPrompts.get())
            .as("approval prompt was shown")
            .isEqualTo(1);

        // 状态：REJECTED
        assertThat(view.status()).isEqualTo(UserIncidentStatus.REJECTED);

        // 摘要必须明确"服务器未发生任何变更"
        assertThat(view.summary())
            .contains("服务器未发生任何变更")
            .contains("已拒绝");

        // 诊断结论
        assertThat(view.diagnosis())
            .contains("order-api")
            .contains("未运行");

        // 建议操作标记为未执行
        assertThat(view.recommendation())
            .contains("未批准");

        // 验证摘要必须包含零副作用保证
        assertThat(view.verificationSummary())
            .contains("未建立修复会话")
            .contains("未执行任何命令")
            .contains("服务器状态与调查前完全一致");

        // 下一步必须明确零副作用
        assertThat(view.nextAction())
            .contains("未执行任何写操作");

        // approvalRequired 在拒绝后为 false
        assertThat(view.approvalRequired()).isFalse();

        // actionExecuted 为空（未执行任何操作）
        assertThat(view.actionExecuted()).isEmpty();
    }

    // ── 场景 2：取消（cancel）─────────────────────────────────────────

    @Test
    void cancelShowsZeroSideEffectTerminal() {
        var counters = new WriteCounters();
        var facade = createFacade(counters);
        var interaction = new CapturingInteraction(ApprovalDecision.CANCEL, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        assertThat(counters.fixSessionCreated.get())
            .as("write session count")
            .isEqualTo(0);
        assertThat(counters.approvalPrompts.get())
            .as("approval prompt was shown")
            .isEqualTo(1);

        // 状态：CANCELLED
        assertThat(view.status()).isEqualTo(UserIncidentStatus.CANCELLED);

        // 摘要必须明确"服务器未发生任何变更"
        assertThat(view.summary())
            .contains("服务器未发生任何变更")
            .contains("已取消");

        // 零副作用保证
        assertThat(view.verificationSummary())
            .contains("未建立修复会话")
            .contains("未执行任何命令")
            .contains("服务器状态与调查前完全一致");

        assertThat(view.nextAction())
            .contains("未执行任何写操作");
    }

    // ── 场景 3：拒绝后 Incident 持久化验证 ────────────────────────────

    @Test
    void rejectedIncidentIsPersistedCorrectly() throws Exception {
        var counters = new WriteCounters();
        var facade = createFacade(counters);
        var interaction = new CapturingInteraction(ApprovalDecision.REJECT, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        // 通过 inspect 读取持久化状态
        InvestigationView persisted = facade.inspect(view.incidentId());
        assertThat(persisted).isNotNull();
        assertThat(persisted.status()).isEqualTo(UserIncidentStatus.REJECTED);
        assertThat(persisted.incidentId()).isEqualTo(view.incidentId());
        assertThat(persisted.targetId()).isEqualTo("fixture");

        // manifest 文件存在且包含 REJECTED
        Path manifest = homeDir.resolve("incidents")
            .resolve(view.incidentId()).resolve("manifest.json");
        assertThat(Files.exists(manifest)).isTrue();
        String content = Files.readString(manifest);
        assertThat(content).contains("REJECTED");
        assertThat(content).doesNotContain("sk-", "api_key", "password", "secret");

        // investigation-result.json 存在
        Path result = homeDir.resolve("incidents")
            .resolve(view.incidentId()).resolve("investigation-result.json");
        assertThat(Files.exists(result)).isTrue();
    }

    // ── 场景 4：取消后 Incident 持久化验证 ────────────────────────────

    @Test
    void cancelledIncidentIsPersistedCorrectly() throws Exception {
        var counters = new WriteCounters();
        var facade = createFacade(counters);
        var interaction = new CapturingInteraction(ApprovalDecision.CANCEL, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        InvestigationView persisted = facade.inspect(view.incidentId());
        assertThat(persisted).isNotNull();
        assertThat(persisted.status()).isEqualTo(UserIncidentStatus.CANCELLED);

        Path manifest = homeDir.resolve("incidents")
            .resolve(view.incidentId()).resolve("manifest.json");
        assertThat(Files.readString(manifest)).contains("CANCELLED");
    }

    // ── 场景 5：拒绝后的 Incident 无秘密泄露 ──────────────────────────

    @Test
    void rejectedIncidentHasNoSecrets() throws Exception {
        var counters = new WriteCounters();
        var facade = createFacade(counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.REJECT, counters));

        Path dir = homeDir.resolve("incidents").resolve(view.incidentId());
        for (Path file : Files.list(dir).toList()) {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file);
                assertThat(content)
                    .as("file " + file.getFileName() + " contains no secrets")
                    .doesNotContain("sk-", "api_key", "Authorization",
                        "Bearer", "password", "secret");
            }
        }
    }

    // ── 场景 6：拒绝后 /ops continue 被正确拒绝 ───────────────────────

    @Test
    void continueOnRejectedIsBlocked() {
        var counters = new WriteCounters();
        var facade = createFacade(counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.REJECT, counters));

        var c2 = new WriteCounters();
        InvestigationView continued = facade.continueIncident(view.incidentId(),
            new CapturingInteraction(ApprovalDecision.APPROVE, c2));

        // continue 被拒绝后返回错误视图（status=INCONCLUSIVE 是 errorView 的默认值）
        assertThat(continued.summary())
            .contains("已被拒绝或取消");
        // continue 被拒绝后不应触发新的审批或修复
        assertThat(c2.approvalPrompts.get()).isEqualTo(0);
        assertThat(c2.fixSessionCreated.get()).isEqualTo(0);
    }

    // ── Factory ─────────────────────────────────────────────────────────

    private OpsInvestigationFacade createFacade(WriteCounters c) {
        OpsInvestigationFacade.InitialReadSessionProvider borrow = tid ->
            new AppDownReadSession(tid);

        OpsInvestigationFacade.FreshReadSessionFactory fresh = tid ->
            new AppDownReadSession(tid);

        OpsInvestigationFacade.FixSessionFactory fix = tid -> {
            c.fixSessionCreated.incrementAndGet();
            throw new IOException("FIXTURE: no real SSH — fix session blocked");
        };

        var facade = new OpsInvestigationFacade(homeDir, borrow, fresh, fix,
            config -> { throw new UnsupportedOperationException("FIXTURE: no LLM"); },
            FIXED_CLOCK);
        facades.add(facade);
        return facade;
    }

    // ── Inner types ─────────────────────────────────────────────────────

    /** 写操作计数器。 */
    static class WriteCounters {
        final AtomicInteger fixSessionCreated = new AtomicInteger(0);
        final AtomicInteger approvalPrompts = new AtomicInteger(0);
    }

    /** 捕获审批交互的 TestInteraction。 */
    record CapturingInteraction(ApprovalDecision decision, WriteCounters c)
            implements InvestigationInteraction {
        @Override public void onProgress(InvestigationProgress p) { }
        @Override public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
            c.approvalPrompts.incrementAndGet();
            // 验证 ApprovalPrompt 包含必要字段
            assertThat(prompt.finding()).contains("order-api");
            assertThat(prompt.suggestedAction()).contains("重启");
            assertThat(prompt.preExecutionProtection()).isNotEmpty();
            assertThat(prompt.postExecutionProtection()).isNotEmpty();
            assertThat(prompt.wontDo()).isNotEmpty();
            assertThat(prompt.approvalValidity()).isNotEmpty();
            assertThat(prompt.actionFingerprint()).isNotEmpty();
            assertThat(prompt.snapshotHash()).isNotEmpty();
            return decision;
        }
        @Override public void onFinalResult(InvestigationView r) {
            // 渲染验证由 RenderTerminalTest 覆盖
        }
    }

    /** 模拟 APP_DOWN 场景的只读会话。
     *
     * <p>返回格式与 DiagnosticSignals.extract() 和
     * DiagnosisReconciler.detectAppDown() 期望的 data.State /
     * data.statusCode 扁平路径一致。
     */
    static class AppDownReadSession implements OpsReadSession {
        private final String tid;
        AppDownReadSession(String tid) { this.tid = tid; }

        @Override
        public McpCallResult callTool(String tool, ObjectNode args) {
            String response = switch (tool) {
                case "service_status" ->
                    "{\"success\":true,\"data\":{\"State\":\"exited\"}}";
                case "container_status" ->
                    "{\"success\":true,\"data\":{\"State\":\"exited\"}}";
                case "http_probe" ->
                    "{\"success\":true,\"data\":{\"statusCode\":503}}";
                case "ports" ->
                    "{\"success\":true,\"data\":{\"bindings\":[{\"PublishedPort\":8080}]}}";
                case "logs" ->
                    "{\"success\":true,\"data\":{\"text\":\"connection refused\"}}";
                default -> "{\"success\":true}";
            };
            return McpCallResult.success(response, List.of());
        }

        @Override public boolean isReady() { return true; }
        @Override public String targetId() { return tid; }
        @Override public void close() { }
    }
}
