package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.InvestigationView;
import com.clawkit.ops.delivery.UserIncidentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 终端渲染冒烟测试：验证用户实际看到的文本内容。
 *
 * <p>不依赖 LineReader 或真实终端 — 直接调用
 * {@link JLineInvestigationInteraction#formatRejectedTerminal(InvestigationView)}
 * 并验证返回的纯文本。
 *
 * <p>语义和安全性已在 {@code AppDownRejectFixtureTest} 中覆盖；
 * 本测试只补终端排版的可见性验证。
 */
class RejectedTerminalRenderTest {

    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    // ── 拒绝场景 ────────────────────────────────────────────────────

    @Test
    void rejectedTerminalHasCorrectTitle() {
        var view = rejectedView("inc-fixture-001");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("操作已拒绝 — 服务器未发生任何变更");
    }

    @Test
    void rejectedTerminalHasDoubleLineBox() {
        var view = rejectedView("inc-fixture-001");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("╔══════════════════════════════════════════════════╗")
            .contains("╚══════════════════════════════════════════════════╝");
    }

    @Test
    void rejectedTerminalHasZeroSideEffectGuarantee() {
        var view = rejectedView("inc-fixture-001");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("┌─ 零副作用保证 ─")
            .contains("✓ 未建立修复会话（write session）")
            .contains("✓ 未执行任何 shell 命令或容器操作")
            .contains("✓ 未修改任何服务状态或配置文件")
            .contains("✓ 服务器状态与调查前完全一致")
            .contains("└──────────────────────────────────────────────────┘");
    }

    @Test
    void rejectedTerminalShowsDiagnosisAndRejectedAction() {
        var view = rejectedView("inc-fixture-001");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("诊断发现：order-api 当前未运行（服务容器已停止）")
            .contains("建议操作：重启 order-api 服务（未执行）");
    }

    @Test
    void rejectedTerminalShowsObservedFacts() {
        var view = new InvestigationView("inc-fixture-001", "fixture", "order-api",
            UserIncidentStatus.REJECTED,
            "服务器未发生任何变更 — 已拒绝",
            List.of("order-api：服务状态 exited", "order-api：HTTP 探测异常"),
            "order-api 当前未运行（服务容器已停止）",
            "重启 order-api 服务（未执行）",
            false, "", "",
            "未执行任何写操作。详情: /ops inspect inc-fixture-001",
            NOW, NOW, "incidents/inc-fixture-001/");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("已确认的现场事实：")
            .contains("· order-api：服务状态 exited")
            .contains("· order-api：HTTP 探测异常");
    }

    @Test
    void rejectedTerminalHasInspectLink() {
        var view = rejectedView("inc-fixture-001");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("/ops inspect inc-fixture-001")
            .contains("Incident：inc-fixture-001");
    }

    // ── 取消场景 ────────────────────────────────────────────────────

    @Test
    void cancelledTerminalHasDifferentTitle() {
        var view = new InvestigationView("inc-fixture-002", "fixture", "order-api",
            UserIncidentStatus.CANCELLED,
            "服务器未发生任何变更 — 已取消",
            List.of(),
            "order-api 当前未运行（服务容器已停止）",
            "未批准修复操作",
            false, "", "",
            "未执行任何写操作。详情: /ops inspect inc-fixture-002",
            NOW, NOW, "incidents/inc-fixture-002/");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        assertThat(output)
            .contains("操作已取消 — 服务器未发生任何变更")
            .doesNotContain("操作已拒绝");
    }

    // ── 边界：无诊断时 ──────────────────────────────────────────────

    @Test
    void rejectedTerminalWithoutDiagnosisDoesNotCrash() {
        var view = new InvestigationView("inc-fixture-003", "fixture", "order-api",
            UserIncidentStatus.REJECTED,
            "服务器未发生任何变更 — 已拒绝",
            List.of(), "", "", false, "", "",
            "未执行任何写操作。详情: /ops inspect inc-fixture-003",
            NOW, NOW, "incidents/inc-fixture-003/");

        String output = JLineInvestigationInteraction.formatRejectedTerminal(view);

        // 不崩溃，仍显示双线框和零副作用保证
        assertThat(output)
            .contains("操作已拒绝 — 服务器未发生任何变更")
            .contains("零副作用保证")
            .doesNotContain("诊断发现：");  // 无诊断时不显示此行
    }

    // ── RESOLVED 成功终态 ───────────────────────────────────────────

    @Test
    void resolvedTerminalHasSuccessTitle() {
        var view = resolvedView("inc-fixture-010");

        String output = JLineInvestigationInteraction.formatResolvedTerminal(view);

        assertThat(output)
            .contains("✓ 问题已恢复")
            .contains("╔══════════════════════════════════════════════════╗");
    }

    @Test
    void resolvedTerminalShowsFullFlow() {
        var view = resolvedView("inc-fixture-010");

        String output = JLineInvestigationInteraction.formatResolvedTerminal(view);

        assertThat(output)
            .contains("诊断发现：")
            .contains("建议操作：")
            .contains("已执行：")
            .contains("独立验证：");
    }

    @Test
    void resolvedTerminalHasInspectLink() {
        var view = resolvedView("inc-fixture-010");

        String output = JLineInvestigationInteraction.formatResolvedTerminal(view);

        assertThat(output)
            .contains("/ops inspect inc-fixture-010")
            .contains("Incident：inc-fixture-010");
    }

    @Test
    void resolvedTerminalDoesNotContainRejectedText() {
        var view = resolvedView("inc-fixture-010");

        String output = JLineInvestigationInteraction.formatResolvedTerminal(view);

        assertThat(output)
            .doesNotContain("操作已拒绝")
            .doesNotContain("零副作用保证")
            .doesNotContain("（未执行）");
    }

    // ── NO_ACTION_REQUIRED 自恢复终态 ───────────────────────────────

    @Test
    void noActionTerminalShowsSelfRecovery() {
        var view = new InvestigationView("inc-fixture-020", "fixture", "order-api",
            UserIncidentStatus.NO_ACTION_REQUIRED, "无需操作",
            List.of(), "", "", false, "", "",
            "无需操作。详情: /ops inspect inc-fixture-020",
            NOW, NOW, "incidents/inc-fixture-020/");

        String output = JLineInvestigationInteraction.formatNoActionTerminal(view);

        assertThat(output)
            .contains("服务已自行恢复 — 无需操作")
            .contains("/ops inspect inc-fixture-020");
    }

    // ── FAILED_NO_EFFECT 执行失败终态 ───────────────────────────────

    @Test
    void failedNoEffectTerminalShowsNoServerChange() {
        var view = new InvestigationView("inc-fixture-030", "fixture", "order-api",
            UserIncidentStatus.FAILED_NO_EFFECT, "操作未执行",
            List.of(), "", "", false, "",
            "已确认未产生远端副作用: restart_service failed",
            "建议人工登录服务器检查。详情: /ops inspect inc-fixture-030",
            NOW, NOW, "incidents/inc-fixture-030/");

        String output = JLineInvestigationInteraction.formatFailedNoEffectTerminal(view);

        assertThat(output)
            .contains("操作未执行 — 服务器无变更")
            .contains("已确认未产生远端副作用")
            .contains("/ops inspect inc-fixture-030");
    }

    // ── NEEDS_HUMAN 终态 ──────────────────────────────────────────

    @Test
    void needsHumanTerminalShowsWarning() {
        var view = new InvestigationView("inc-fixture-040", "fixture", "order-api",
            UserIncidentStatus.NEEDS_HUMAN, "需要人工处理",
            List.of(), "", "", false, "restart_service(order-api)",
            "执行结果未知，需要人工检查远端状态",
            "建议人工登录服务器检查。详情: /ops inspect inc-fixture-040",
            NOW, NOW, "incidents/inc-fixture-040/");

        String output = JLineInvestigationInteraction.formatNeedsHumanTerminal(view);

        assertThat(output)
            .contains("需要人工处理")
            .contains("已执行：restart_service(order-api)")
            .contains("/ops inspect inc-fixture-040");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static InvestigationView rejectedView(String incidentId) {
        return new InvestigationView(incidentId, "fixture", "order-api",
            UserIncidentStatus.REJECTED,
            "服务器未发生任何变更 — 已拒绝",
            List.of("order-api：服务状态 exited", "order-api：HTTP 探测异常"),
            "order-api 当前未运行（服务容器已停止）",
            "重启 order-api 服务",
            false, "", "",
            "未执行任何写操作。详情: /ops inspect " + incidentId,
            NOW, NOW, "incidents/" + incidentId + "/");
    }

    private static InvestigationView resolvedView(String incidentId) {
        return new InvestigationView(incidentId, "fixture", "order-api",
            UserIncidentStatus.RESOLVED,
            "问题已恢复",
            List.of("order-api：服务状态 running", "order-api：HTTP 探测正常"),
            "order-api 当前未运行（服务容器已停止）",
            "重启 order-api 服务",
            false,
            "restart_service(order-api)",
            "独立验证通过：服务进程、HTTP 探测、容器状态正常，无新增错误",
            "问题已恢复。详情: /ops inspect " + incidentId,
            NOW, NOW, "incidents/" + incidentId + "/");
    }
}
