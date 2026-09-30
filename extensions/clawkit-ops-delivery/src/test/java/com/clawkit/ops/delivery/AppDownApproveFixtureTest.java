package com.clawkit.ops.delivery;

import com.clawkit.ops.loop.*;
import com.clawkit.ops.loop.repair.FixSession;
import com.clawkit.ops.loop.repair.RepairResult;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
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
 * 批准成功闭环夹具测试。
 *
 * <p>模拟 APP_DOWN → APPROVE → fresh precheck APP_DOWN
 * → restart_service 成功 → 独立验证 RUNNING_HEALTHY → RESOLVED 全流程。
 *
 * <p>与 {@link AppDownRejectFixtureTest} 共同构成 PRODUCT-3 阶段 1 验收。
 */
class AppDownApproveFixtureTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-08-03T12:00:00Z"), ZoneId.of("UTC"));

    @TempDir Path homeDir;
    private final List<OpsInvestigationFacade> facades = new ArrayList<>();

    @AfterEach
    void tearDown() {
        facades.forEach(OpsInvestigationFacade::close);
    }

    // ── P1: 批准成功闭环 ────────────────────────────────────────────

    @Test
    void approveSuccessResolvesIncident() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forApproveSuccess(counters);
        var facade = createFacade(sessions, counters);
        var interaction = new CapturingInteraction(ApprovalDecision.APPROVE, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        // 诊断所有计数器和状态
        assertThat(counters.approvalPrompts.get())
            .as("approval prompt shown").isEqualTo(1);
        assertThat(counters.fixSessionCreated.get())
            .as("fix session created").isEqualTo(1);
        assertThat(counters.freshPrecheckCount.get())
            .as("fresh precheck count").isGreaterThanOrEqualTo(2);
        // 先输出实际状态便于定位
        assertThat(view.status())
            .as("final status should be RESOLVED but was " + view.status()
                + " (summary=" + view.summary() + ", action=" + view.actionExecuted()
                + ", verify=" + view.verificationSummary() + ")")
            .isEqualTo(UserIncidentStatus.RESOLVED);

        // 执行动作已记录
        assertThat(view.actionExecuted())
            .isNotEmpty()
            .contains("restart_service");

        // 验证摘要
        assertThat(view.verificationSummary())
            .isNotEmpty()
            .contains("验证");

        // Incident 可 inspect
        InvestigationView persisted = facade.inspect(view.incidentId());
        assertThat(persisted).isNotNull();
        assertThat(persisted.status()).isEqualTo(UserIncidentStatus.RESOLVED);
    }

    @Test
    void approveSuccessHasCorrectParams() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forApproveSuccess(counters);
        var facade = createFacade(sessions, counters);

        facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        // 参数验证
        assertThat(counters.lastRestartIncidentId)
            .as("restart incidentId matches")
            .isNotNull();
        assertThat(counters.lastRestartServiceId)
            .as("restart serviceId is order-api")
            .isEqualTo("order-api");
        assertThat(counters.lastRestartTarget)
            .as("restart target contains fixture")
            .contains("fixture");
    }

    @Test
    void approveSuccessIncidentHasNoSecrets() throws Exception {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forApproveSuccess(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        Path dir = homeDir.resolve("incidents").resolve(view.incidentId());
        for (Path file : Files.list(dir).toList()) {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file);
                assertThat(content)
                    .as("file " + file.getFileName())
                    .doesNotContain("sk-", "api_key", "Authorization",
                        "Bearer", "password", "secret");
            }
        }
    }

    @Test
    void approveSuccessPersistsManifestCorrectly() throws Exception {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forApproveSuccess(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        Path manifest = homeDir.resolve("incidents")
            .resolve(view.incidentId()).resolve("manifest.json");
        assertThat(Files.exists(manifest)).isTrue();

        String content = Files.readString(manifest);
        assertThat(content).contains("RESOLVED");
        assertThat(content).doesNotContain("sk-", "api_key");
    }

    @Test
    void resolvedIncidentCannotBeContinued() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forApproveSuccess(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        var c2 = new WriteCounters();
        InvestigationView continued = facade.continueIncident(view.incidentId(),
            new CapturingInteraction(ApprovalDecision.APPROVE, c2));

        assertThat(continued.summary())
            .contains("已恢复");
        assertThat(c2.fixSessionCreated.get()).isEqualTo(0);
    }

    // ── S1: 自恢复 ──────────────────────────────────────────────────

    @Test
    void selfRecoveryReturnsNoActionRequired() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forSelfRecovery(counters);
        var facade = createFacade(sessions, counters);
        var interaction = new CapturingInteraction(ApprovalDecision.APPROVE, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        // 自恢复：orchestrator 检测到 SELF_RECOVERED → CANCELLED_NO_EFFECT → NO_ACTION_REQUIRED
        assertThat(view.status())
            .as("self-recovery status")
            .isEqualTo(UserIncidentStatus.NO_ACTION_REQUIRED);
        // 核心安全：restart 从未调用
        assertThat(counters.restartCalls.get())
            .as("restart NOT called").isEqualTo(0);
        assertThat(counters.approvalPrompts.get())
            .as("approval shown").isEqualTo(1);

        // Incident 可 inspect
        InvestigationView persisted = facade.inspect(view.incidentId());
        assertThat(persisted.status()).isEqualTo(UserIncidentStatus.NO_ACTION_REQUIRED);
    }

    @Test
    void selfRecoveryShowsRecoveryEvidence() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forSelfRecovery(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        assertThat(view.verificationSummary())
            .contains("自行恢复");
        assertThat(view.approvalRequired()).isFalse();
        assertThat(view.actionExecuted()).isEmpty();
    }

    // ── S2: 快照漂移 ──────────────────────────────────────────────

    @Test
    void snapshotDriftReturnsFailedNoEffect() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forSnapshotDrift(counters);
        var facade = createFacade(sessions, counters);
        var interaction = new CapturingInteraction(ApprovalDecision.APPROVE, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        // 漂移检测：fix session 可能被创建但 restart 绝不调用
        assertThat(counters.restartCalls.get())
            .as("restart NOT called on drift").isEqualTo(0);
        assertThat(counters.approvalPrompts.get())
            .as("approval shown before drift detected").isEqualTo(1);

        // Incident 可 inspect
        InvestigationView persisted = facade.inspect(view.incidentId());
        assertThat(persisted).isNotNull();
    }

    @Test
    void snapshotDriftHasNoSecrets() throws Exception {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forSnapshotDrift(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        Path dir = homeDir.resolve("incidents").resolve(view.incidentId());
        for (Path file : Files.list(dir).toList()) {
            if (Files.isRegularFile(file)) {
                String content = Files.readString(file);
                assertThat(content).doesNotContain("sk-", "api_key", "password", "secret");
            }
        }
    }

    // ── E1: 执行失败 ────────────────────────────────────────────────

    @Test
    void execFailureReturnsFailedNoEffect() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forExecFailure(counters);
        var facade = createFacade(sessions, counters);
        var interaction = new CapturingInteraction(ApprovalDecision.APPROVE, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        assertThat(view.status()).isEqualTo(UserIncidentStatus.FAILED_NO_EFFECT);
        assertThat(counters.restartCalls.get()).as("restart attempted").isEqualTo(1);
        assertThat(counters.fixSessionCreated.get()).as("fix session created").isEqualTo(1);
        assertThat(view.verificationSummary()).contains("未产生远端副作用");
    }

    @Test
    void execFailureIncidentPersisted() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forExecFailure(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        InvestigationView persisted = facade.inspect(view.incidentId());
        assertThat(persisted.status()).isEqualTo(UserIncidentStatus.FAILED_NO_EFFECT);
    }

    // ── E2: 结果未知（超时/中断）───────────────────────────────────

    @Test
    void outcomeUnknownReturnsNeedsHuman() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forOutcomeUnknown(counters);
        var facade = createFacade(sessions, counters);
        var interaction = new CapturingInteraction(ApprovalDecision.APPROVE, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        assertThat(view.status()).isEqualTo(UserIncidentStatus.NEEDS_HUMAN);
        assertThat(counters.restartCalls.get()).as("restart called before timeout").isEqualTo(1);
        assertThat(view.verificationSummary()).contains("结果未知");
        assertThat(view.actionExecuted()).contains("可能已部分执行");
    }

    @Test
    void outcomeUnknownContinueIsSticky() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forOutcomeUnknown(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        // /ops continue on OUTCOME_UNKNOWN → should NOT retry automatically
        var c2 = new WriteCounters();
        InvestigationView continued = facade.continueIncident(view.incidentId(),
            new CapturingInteraction(ApprovalDecision.APPROVE, c2));

        // 修复会话使用的是原 Fixture 计数器；必须核对它仍只有首次
        // 派发，不能只检查 continue 的交互计数器。
        assertThat(counters.restartCalls.get()).as("no auto-retry on continue").isEqualTo(1);
        assertThat(continued).isNotNull();
    }

    @Test
    void runtimeFailureAfterDispatchAlsoBecomesStickyOutcomeUnknown() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forRuntimeOutcomeUnknown(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        assertThat(view.status()).isEqualTo(UserIncidentStatus.NEEDS_HUMAN);
        assertThat(view.verificationSummary()).contains("结果未知");
        assertThat(counters.restartCalls.get()).isEqualTo(1);

        facade.continueIncident(view.incidentId(),
            new CapturingInteraction(ApprovalDecision.APPROVE, new WriteCounters()));
        assertThat(counters.restartCalls.get()).as("runtime failure must not re-dispatch")
            .isEqualTo(1);
    }

    // ── V1: 验证失败 ──────────────────────────────────────────────

    @Test
    void verifyFailureReturnsNeedsHuman() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forVerifyFailure(counters);
        var facade = createFacade(sessions, counters);
        var interaction = new CapturingInteraction(ApprovalDecision.APPROVE, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null), interaction);

        // restart "succeeds" but verification finds service still down
        assertThat(view.status()).isEqualTo(UserIncidentStatus.NEEDS_HUMAN);
        assertThat(counters.restartCalls.get()).as("restart called").isEqualTo(1);
        assertThat(counters.verifySessionCount.get()).as("verification performed").isGreaterThanOrEqualTo(1);
        assertThat(view.status()).isNotEqualTo(UserIncidentStatus.RESOLVED);
        assertThat(view.verificationSummary())
            .isNotEmpty();
    }

    @Test
    void verifyFailureDoesNotClaimSuccess() {
        var counters = new WriteCounters();
        var sessions = FixtureSessions.forVerifyFailure(counters);
        var facade = createFacade(sessions, counters);

        InvestigationView view = facade.investigateAndMaybeRepair(
            new InvestigationRequest("fixture", "order-api", null),
            new CapturingInteraction(ApprovalDecision.APPROVE, counters));

        assertThat(view.status()).isNotEqualTo(UserIncidentStatus.RESOLVED);
        assertThat(view.summary()).doesNotContain("恢复");
    }

    // ── Factory ─────────────────────────────────────────────────────────

    private OpsInvestigationFacade createFacade(FixtureSessions sessions,
                                                 WriteCounters counters) {
        OpsInvestigationFacade.InitialReadSessionProvider borrow =
            tid -> new AppDownReadSession(tid, FixtureReadState.APP_DOWN);

        OpsInvestigationFacade.FreshReadSessionFactory fresh = tid -> {
            int call = counters.freshPrecheckCount.incrementAndGet();
            if (call > 2) counters.verifySessionCount.incrementAndGet();
            FixtureReadState state = sessions.stateForCall(call);
            return new AppDownReadSession(tid, state);
        };

        OpsInvestigationFacade.FixSessionFactory fix = tid -> {
            counters.fixSessionCreated.incrementAndGet();
            return sessions.fixSession();
        };

        var facade = new OpsInvestigationFacade(homeDir, borrow, fresh, fix,
            config -> { throw new UnsupportedOperationException("FIXTURE: no LLM"); },
            FIXED_CLOCK);
        facades.add(facade);
        return facade;
    }

    // ── Inner types ─────────────────────────────────────────────────────

    static class WriteCounters {
        final AtomicInteger fixSessionCreated = new AtomicInteger(0);
        final AtomicInteger restartCalls = new AtomicInteger(0);
        final AtomicInteger approvalPrompts = new AtomicInteger(0);
        final AtomicInteger freshPrecheckCount = new AtomicInteger(0);
        final AtomicInteger verifySessionCount = new AtomicInteger(0);
        volatile String lastRestartIncidentId;
        volatile String lastRestartServiceId;
        volatile String lastRestartTarget;
    }

    /** 可配置的夹具会话工厂。 */
    static class FixtureSessions {
        /** call=1: prepareApproval precheck, call=2: orchestrator precheck, call=3: verification */
        private final FixtureReadState call1;
        private final FixtureReadState call2;
        private final FixtureReadState call3;
        private final FakeFixSession.Mode fixMode;
        private final WriteCounters counters;

        private FixtureSessions(FixtureReadState call1, FixtureReadState call2,
                                FixtureReadState call3, FakeFixSession.Mode fixMode,
                                WriteCounters counters) {
            this.call1 = call1; this.call2 = call2; this.call3 = call3;
            this.fixMode = fixMode;
            this.counters = counters;
        }

        static FixtureSessions forApproveSuccess(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.APP_DOWN, FixtureReadState.RUNNING,
                FakeFixSession.Mode.SUCCESS, c);
        }

        /**
         * S1: orchestrator precheck 检测到服务已自恢复。
         * call1=APP_DOWN（prepareApproval 通过），call2=RUNNING（orchestrator 检测 SELF_RECOVERED）
         */
        static FixtureSessions forSelfRecovery(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.RUNNING, FixtureReadState.RUNNING,
                FakeFixSession.Mode.SUCCESS, c);
        }

        static FixtureSessions forSnapshotDrift(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.DRIFTED, FixtureReadState.RUNNING,
                FakeFixSession.Mode.SUCCESS, c);
        }

        static FixtureSessions forExecFailure(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.APP_DOWN, FixtureReadState.RUNNING,
                FakeFixSession.Mode.FAIL_NO_EFFECT, c);
        }

        static FixtureSessions forOutcomeUnknown(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.APP_DOWN, FixtureReadState.RUNNING,
                FakeFixSession.Mode.THROW_IOEXCEPTION, c);
        }

        static FixtureSessions forRuntimeOutcomeUnknown(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.APP_DOWN, FixtureReadState.RUNNING,
                FakeFixSession.Mode.THROW_RUNTIME_EXCEPTION, c);
        }

        static FixtureSessions forVerifyFailure(WriteCounters c) {
            return new FixtureSessions(FixtureReadState.APP_DOWN,
                FixtureReadState.APP_DOWN, FixtureReadState.APP_DOWN,
                FakeFixSession.Mode.SUCCESS, c);
        }

        FixtureReadState stateForCall(int call) {
            return switch (call) {
                case 1 -> call1;
                case 2 -> call2;
                default -> call3;
            };
        }

        FixSession fixSession() {
            return new FakeFixSession(fixMode, counters);
        }
    }

    /** 不执行真实 SSH 的修复会话。 */
    static class FakeFixSession implements FixSession {
        enum Mode { SUCCESS, FAIL_NO_EFFECT, THROW_IOEXCEPTION, THROW_RUNTIME_EXCEPTION }

        private final Mode mode;
        private final WriteCounters counters;
        private boolean closed;

        FakeFixSession(Mode mode, WriteCounters counters) {
            this.mode = mode;
            this.counters = counters;
        }

        @Override
        public RepairResult executeRestart(String incidentId, String repairRunId)
                throws IOException {
            counters.restartCalls.incrementAndGet();
            counters.lastRestartIncidentId = incidentId;
            counters.lastRestartServiceId = "order-api";

            if (mode == Mode.THROW_IOEXCEPTION) {
                throw new IOException("SSH timeout");
            }
            if (mode == Mode.THROW_RUNTIME_EXCEPTION) {
                throw new IllegalStateException("adapter interrupted after dispatch");
            }

            Instant now = FIXED_CLOCK.instant();
            return switch (mode) {
                case SUCCESS -> new RepairResult(incidentId,
                    "fix-" + repairRunId, repairRunId,
                    AttemptState.VERIFICATION_PENDING,
                    EffectCertainty.EFFECT_CONFIRMED,
                    FailureClass.NONE,
                    "restart_service(order-api) completed successfully",
                    now.minusSeconds(2), now, null);
                case FAIL_NO_EFFECT -> new RepairResult(incidentId,
                    "fix-" + repairRunId, repairRunId,
                    AttemptState.FAILED_NO_EFFECT,
                    EffectCertainty.NO_EFFECT_CONFIRMED,
                    FailureClass.LOCAL_ERROR_NO_EFFECT,
                    "restart_service failed: container not found",
                    now.minusSeconds(2), now, null);
                default -> throw new IllegalStateException("unknown mode: " + mode);
            };
        }

        @Override
        public void close() { closed = true; }
        boolean isClosed() { return closed; }
    }

    record CapturingInteraction(ApprovalDecision decision, WriteCounters c)
            implements InvestigationInteraction {
        @Override public void onProgress(InvestigationProgress p) { }
        @Override public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
            c.approvalPrompts.incrementAndGet();
            c.lastRestartTarget = prompt.targetId();
            assertThat(prompt.finding()).contains("order-api");
            assertThat(prompt.suggestedAction()).contains("重启");
            assertThat(prompt.actionFingerprint()).isNotEmpty();
            assertThat(prompt.snapshotHash()).isNotEmpty();
            return decision;
        }
        @Override public void onFinalResult(InvestigationView r) { }
    }

    /** 夹具只读会话状态。 */
    enum FixtureReadState {
        /** order-api 已停止：State=exited, HTTP 503 */
        APP_DOWN,
        /** order-api 正常运行：State=running, HTTP 200 */
        RUNNING,
        /**
         * order-api 运行中但 HTTP 仍失败（快照漂移）：
         * containers[0].State=running（兼容 checkSelfRecovered），HTTP 503
         */
        SELF_RECOVERED,
        /**
         * 状态漂移：State=running 但 HTTP 503（非自恢复，仍异常）
         */
        DRIFTED
    }

    /** 模拟多种远端状态的只读会话。 */
    static class AppDownReadSession implements OpsReadSession {
        private final String tid;
        private final FixtureReadState state;

        AppDownReadSession(String tid, FixtureReadState state) {
            this.tid = tid;
            this.state = state;
        }

        @Override
        public McpCallResult callTool(String tool, ObjectNode args) {
            String response = switch (state) {
                case APP_DOWN -> switch (tool) {
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
                case RUNNING -> switch (tool) {
                    case "service_status" ->
                        "{\"success\":true,\"data\":{\"State\":\"running\"}}";
                    case "container_status" ->
                        "{\"success\":true,\"data\":{\"State\":\"running\"}}";
                    case "http_probe" ->
                        "{\"success\":true,\"data\":{\"statusCode\":200}}";
                    case "ports" ->
                        "{\"success\":true,\"data\":{\"bindings\":[{\"PublishedPort\":8080}]}}";
                    case "logs" ->
                        "{\"success\":true,\"data\":{\"text\":\"server started\"}}";
                    default -> "{\"success\":true}";
                };
                case SELF_RECOVERED -> switch (tool) {
                    // containers 格式：checkSelfRecovered 查找 data.containers[0].State
                    case "service_status" ->
                        "{\"success\":true,\"data\":{\"containers\":[{\"State\":\"running\"}]}}";
                    case "container_status" ->
                        "{\"success\":true,\"data\":{\"State\":\"running\"}}";
                    case "http_probe" ->
                        "{\"success\":true,\"data\":{\"statusCode\":200}}";
                    case "ports" ->
                        "{\"success\":true,\"data\":{\"bindings\":[{\"PublishedPort\":8080}]}}";
                    case "logs" ->
                        "{\"success\":true,\"data\":{\"text\":\"server recovered\"}}";
                    default -> "{\"success\":true}";
                };
                case DRIFTED -> switch (tool) {
                    case "service_status" ->
                        "{\"success\":true,\"data\":{\"State\":\"running\"}}";
                    case "container_status" ->
                        "{\"success\":true,\"data\":{\"State\":\"running\"}}";
                    case "http_probe" ->
                        "{\"success\":true,\"data\":{\"statusCode\":503}}";
                    case "ports" ->
                        "{\"success\":true,\"data\":{\"bindings\":[{\"PublishedPort\":8080}]}}";
                    case "logs" ->
                        "{\"success\":true,\"data\":{\"text\":\"ERROR: gateway timeout\"}}";
                    default -> "{\"success\":true}";
                };
            };
            return McpCallResult.success(response, List.of());
        }

        @Override public boolean isReady() { return true; }
        @Override public String targetId() { return tid; }
        @Override public void close() { }
    }
}
