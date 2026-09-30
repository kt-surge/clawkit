package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.ApprovalDecision;
import com.clawkit.ops.delivery.ApprovalPrompt;
import com.clawkit.ops.delivery.InvestigationInteraction;
import com.clawkit.ops.delivery.InvestigationProgress;
import com.clawkit.ops.delivery.InvestigationView;
import com.clawkit.ops.delivery.UserIncidentStatus;
import com.clawkit.ops.loop.autonomy.ShadowReviewStore;
import com.clawkit.cli.ConsoleRenderer;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

/**
 * JLine3 implementation of {@link InvestigationInteraction}.
 * Uses the existing REPL {@link LineReader} — no Scanner/System.in threads.
 *
 * <p>OPS-PRODUCT-LOOP-1 §9.
 */
public final class JLineInvestigationInteraction implements InvestigationInteraction {

    private final LineReader reader;
    private DogfoodLogger dogfood;

    public JLineInvestigationInteraction(LineReader reader) {
        this.reader = reader;
    }

    /** 注入 dogfood 日志器（可选，无注入时静默跳过）。 */
    public void setDogfoodLogger(DogfoodLogger logger) {
        this.dogfood = logger;
    }

    /** 调查命令提交时开始记录；日志失败不会影响 Ops 主流程。 */
    void beginDogfoodTask(String targetId) {
        if (dogfood != null) dogfood.beginTask(targetId, "INVESTIGATE", "REMOTE_READONLY");
    }

    void logDogfoodFeedback(String incidentId, Boolean understood, Boolean fellBackToSsh,
                            String description, String priority) {
        if (dogfood != null) dogfood.logFeedback(incidentId, understood, fellBackToSsh, description, priority);
    }

    /**
     * Records only the first durable A3 counterfactual review.
     *
     * <p>A replayed immutable review is not another human decision and must
     * not inflate the dogfood sample used for later manual assessment.
     */
    void logDogfoodShadowReview(ShadowReviewStore.RecordedReview recorded) {
        if (dogfood != null && recorded != null && recorded.created()) {
            dogfood.logShadowReview(recorded.review());
        }
    }

    DogfoodLogger.Summary dogfoodSummary() throws java.io.IOException {
        return dogfood == null ? DogfoodLogger.Summary.missing() : dogfood.summary();
    }

    @Override
    public void onProgress(InvestigationProgress progress) {
        String prefix = "  [" + progress.step() + "/" + progress.totalSteps() + "]";
        System.out.println(ConsoleRenderer.GRAY + prefix + " " + progress.message()
            + ConsoleRenderer.RESET);
    }

    @Override
    public ApprovalDecision requestApproval(ApprovalPrompt prompt) {
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════╗");
        System.out.println("  ║              修复审批                             ║");
        System.out.println("  ╠══════════════════════════════════════════════════╣");
        System.out.println("  ║  目标：" + ConsoleRenderer.padRight(prompt.targetId(), 43) + "║");
        System.out.println("  ║  服务：" + ConsoleRenderer.padRight(prompt.serviceId(), 43) + "║");
        System.out.println("  ╚══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  ▸ 发现：" + prompt.finding());
        if (prompt.whyAppDown() != null && !prompt.whyAppDown().isEmpty()) {
            System.out.println("  ▸ 判断依据：" + prompt.whyAppDown());
        }
        System.out.println("  ▸ 建议执行：" + prompt.suggestedAction());
        System.out.println("  ▸ 影响对象：" + prompt.impact());
        if (prompt.riskLevel() != null && !prompt.riskLevel().isEmpty()) {
            System.out.println("  ▸ 风险等级：" + prompt.riskLevel());
        }
        System.out.println("  ▸ 执行前保护：" + prompt.preExecutionProtection());
        System.out.println("  ▸ 执行后保护：" + prompt.postExecutionProtection());
        if (prompt.wontDo() != null && !prompt.wontDo().isEmpty()) {
            System.out.println("  ▸ 不会执行：" + prompt.wontDo());
        }
        System.out.println("  ▸ 批准有效期：" + prompt.approvalValidity());
        System.out.println();

        if (prompt.evidenceSummary() != null && !prompt.evidenceSummary().isEmpty()) {
            System.out.println("  关键证据：");
            for (String ev : prompt.evidenceSummary()) {
                System.out.println("    · " + ev);
            }
            System.out.println();
        }

        System.out.print("  请输入 [approve / reject / cancel]: ");

        if (dogfood != null) dogfood.markApprovalShown();

        try {
            String line = reader.readLine("");
            if (line == null) {
                System.out.println("\n  审批：EOF → CANCELLED\n");
                if (dogfood != null) dogfood.recordDecision(ApprovalDecision.EOF);
                return ApprovalDecision.EOF;
            }
            String input = line.strip().toLowerCase();
            ApprovalDecision decision = switch (input) {
                case "approve", "a", "y", "yes" -> {
                    System.out.println("\n  ✓ 已批准\n");
                    yield ApprovalDecision.APPROVE;
                }
                case "reject", "r", "n", "no" -> {
                    System.out.println("\n  ✗ 已拒绝。未执行任何写操作。\n");
                    yield ApprovalDecision.REJECT;
                }
                case "cancel", "c" -> {
                    System.out.println("\n  — 已取消\n");
                    yield ApprovalDecision.CANCEL;
                }
                default -> {
                    System.out.println("\n  无法识别的输入，视为取消。\n");
                    yield ApprovalDecision.CANCEL;
                }
            };
            if (dogfood != null) dogfood.recordDecision(decision);
            return decision;
        } catch (UserInterruptException e) {
            System.out.println("\n  审批：Ctrl+C → INTERRUPTED\n");
            if (dogfood != null) dogfood.recordDecision(ApprovalDecision.INTERRUPTED);
            return ApprovalDecision.INTERRUPTED;
        } catch (EndOfFileException e) {
            System.out.println("\n  审批：EOF → CANCELLED\n");
            if (dogfood != null) dogfood.recordDecision(ApprovalDecision.EOF);
            return ApprovalDecision.EOF;
        }
    }

    @Override
    public void onFinalResult(InvestigationView result) {
        if (dogfood != null) {
            dogfood.log(result.targetId(), "INVESTIGATE", null,
                result.status(), result.incidentId());
        }
        System.out.println();

        // ── 拒绝 / 取消：醒目的中文终态，明确零副作用保证 ──────────
        if (result.status() == UserIncidentStatus.REJECTED
            || result.status() == UserIncidentStatus.CANCELLED) {
            renderRejectedTerminal(result);
            return;
        }

        if (result.status() == UserIncidentStatus.RESOLVED) {
            System.out.print(formatResolvedTerminal(result));
            return;
        } else if (result.status() == UserIncidentStatus.NO_ACTION_REQUIRED) {
            System.out.print(formatNoActionTerminal(result));
            return;
        } else if (result.status() == UserIncidentStatus.FAILED_NO_EFFECT) {
            System.out.print(formatFailedNoEffectTerminal(result));
            return;
        } else if (result.status() == UserIncidentStatus.INCONCLUSIVE) {
            System.out.println("  ? 无法确定原因");
        } else if (result.status() == UserIncidentStatus.AWAITING_APPROVAL) {
            System.out.println("  ⚠ 需要审批修复操作");
        } else if (result.status() == UserIncidentStatus.NEEDS_HUMAN) {
            System.out.print(formatNeedsHumanTerminal(result));
            return;
        } else {
            System.out.println("  状态：" + result.status());
        }

        if (result.observedFacts() != null && !result.observedFacts().isEmpty()) {
            System.out.println("  已确认：");
            int factLimit = result.status() == UserIncidentStatus.INCONCLUSIVE ? 8 : 5;
            result.observedFacts().stream().limit(factLimit)
                .forEach(fact -> System.out.println("    - " + fact));
        }

        if (result.diagnosis() != null && !result.diagnosis().isBlank()
            && !"未完成诊断".equals(result.diagnosis())) {
            System.out.println("  诊断：" + result.diagnosis());
        }
        if (result.recommendation() != null && !result.recommendation().isBlank()) {
            System.out.println("  建议：" + result.recommendation());
        }
        if (result.actionExecuted() != null && !result.actionExecuted().isBlank()) {
            System.out.println("  执行：" + result.actionExecuted());
        }
        if (result.verificationSummary() != null && !result.verificationSummary().isBlank()) {
            System.out.println("  验证：" + result.verificationSummary());
        }
        if (result.nextAction() != null && !result.nextAction().isBlank()) {
            System.out.println();
            System.out.println("  → " + result.nextAction());
        }
        System.out.println();
        System.out.println(ConsoleRenderer.GRAY + "  Incident：" + result.incidentId()
            + "  |  详情：/ops inspect " + result.incidentId() + ConsoleRenderer.RESET);
        System.out.println();
    }

    /**
     * 拒绝 / 取消终态展示。
     *
     * <p>明确告知用户：服务器未发生任何变更，所有操作均为只读。
     * 这是 OPS 第三条产品闭环（审批决策体验）的核心交付物。
     */
    private void renderRejectedTerminal(InvestigationView result) {
        System.out.print(formatRejectedTerminal(result));
    }

    /**
     * 构建拒绝/取消终态的完整终端文本（不含颜色转义）。
     * Package-private 以支持冒烟测试直接验证输出内容。
     */
    static String formatRejectedTerminal(InvestigationView result) {
        boolean isRejected = result.status() == UserIncidentStatus.REJECTED;
        String title = isRejected ? "操作已拒绝 — 服务器未发生任何变更" : "操作已取消 — 服务器未发生任何变更";

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("  ╔══════════════════════════════════════════════════╗\n");
        sb.append("  ║  ").append(ConsoleRenderer.padRight(title, 48)).append("  ║\n");
        sb.append("  ╚══════════════════════════════════════════════════╝\n");
        sb.append("\n");

        if (result.diagnosis() != null && !result.diagnosis().isBlank()
            && !"未完成诊断".equals(result.diagnosis())) {
            sb.append("  诊断发现：").append(result.diagnosis()).append("\n");
        }

        if (result.recommendation() != null && !result.recommendation().isBlank()) {
            sb.append("  建议操作：").append(result.recommendation()).append("（未执行）\n");
        }

        if (result.observedFacts() != null && !result.observedFacts().isEmpty()) {
            sb.append("\n");
            sb.append("  已确认的现场事实：\n");
            result.observedFacts().stream().limit(5)
                .forEach(fact -> sb.append("    · ").append(fact).append("\n"));
        }

        sb.append("\n");
        sb.append("  ┌─ 零副作用保证 ──────────────────────────────────┐\n");
        sb.append("  │ ✓ 未建立修复会话（write session）               │\n");
        sb.append("  │ ✓ 未执行任何 shell 命令或容器操作               │\n");
        sb.append("  │ ✓ 未修改任何服务状态或配置文件                  │\n");
        sb.append("  │ ✓ 服务器状态与调查前完全一致                    │\n");
        sb.append("  └──────────────────────────────────────────────────┘\n");

        sb.append("\n");
        sb.append("  → ").append(result.nextAction()).append("\n");
        sb.append("\n");
        sb.append("  Incident：").append(result.incidentId())
            .append("  |  详情：/ops inspect ").append(result.incidentId()).append("\n");
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 构建修复成功终态的完整终端文本。
     * Package-private 以支持冒烟测试直接验证输出内容。
     */
    static String formatResolvedTerminal(InvestigationView result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("  ╔══════════════════════════════════════════════════╗\n");
        sb.append("  ║  ").append(ConsoleRenderer.padRight("✓ 问题已恢复", 48)).append("  ║\n");
        sb.append("  ╚══════════════════════════════════════════════════╝\n");
        sb.append("\n");

        if (result.diagnosis() != null && !result.diagnosis().isBlank()
            && !"未完成诊断".equals(result.diagnosis())) {
            sb.append("  诊断发现：").append(result.diagnosis()).append("\n");
        }

        if (result.recommendation() != null && !result.recommendation().isBlank()) {
            sb.append("  建议操作：").append(result.recommendation()).append("\n");
        }

        if (result.actionExecuted() != null && !result.actionExecuted().isBlank()) {
            sb.append("  已执行：").append(result.actionExecuted()).append("\n");
        }

        if (result.verificationSummary() != null && !result.verificationSummary().isBlank()) {
            sb.append("  独立验证：").append(result.verificationSummary()).append("\n");
        }

        if (result.observedFacts() != null && !result.observedFacts().isEmpty()) {
            sb.append("\n");
            sb.append("  验证确认的事实：\n");
            result.observedFacts().stream().limit(5)
                .forEach(fact -> sb.append("    · ").append(fact).append("\n"));
        }

        sb.append("\n");
        sb.append("  → ").append(result.nextAction()).append("\n");
        sb.append("\n");
        sb.append("  Incident：").append(result.incidentId())
            .append("  |  详情：/ops inspect ").append(result.incidentId()).append("\n");
        sb.append("\n");
        return sb.toString();
    }

    /** 自恢复终态：服务在执行前已自行恢复 */
    static String formatNoActionTerminal(InvestigationView result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("  ╔══════════════════════════════════════════════════╗\n");
        sb.append("  ║  ").append(ConsoleRenderer.padRight("服务已自行恢复 — 无需操作", 48)).append("  ║\n");
        sb.append("  ╚══════════════════════════════════════════════════╝\n");
        sb.append("\n");
        if (result.verificationSummary() != null && !result.verificationSummary().isBlank()) {
            sb.append("  ").append(result.verificationSummary()).append("\n");
        }
        sb.append("\n");
        if (result.nextAction() != null && !result.nextAction().isBlank()) {
            sb.append("  → ").append(result.nextAction()).append("\n");
        }
        sb.append("\n");
        sb.append("  Incident：").append(result.incidentId())
            .append("  |  详情：/ops inspect ").append(result.incidentId()).append("\n");
        sb.append("\n");
        return sb.toString();
    }

    /** 执行失败终态：已确认未产生远端副作用 */
    static String formatFailedNoEffectTerminal(InvestigationView result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("  ╔══════════════════════════════════════════════════╗\n");
        sb.append("  ║  ").append(ConsoleRenderer.padRight("操作未执行 — 服务器无变更", 48)).append("  ║\n");
        sb.append("  ╚══════════════════════════════════════════════════╝\n");
        sb.append("\n");
        if (result.verificationSummary() != null && !result.verificationSummary().isBlank()) {
            sb.append("  ").append(result.verificationSummary()).append("\n");
        }
        sb.append("\n");
        if (result.nextAction() != null && !result.nextAction().isBlank()) {
            sb.append("  → ").append(result.nextAction()).append("\n");
        }
        sb.append("\n");
        sb.append("  Incident：").append(result.incidentId())
            .append("  |  详情：/ops inspect ").append(result.incidentId()).append("\n");
        sb.append("\n");
        return sb.toString();
    }

    /** 需要人工处理终态 */
    static String formatNeedsHumanTerminal(InvestigationView result) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append("  ╔══════════════════════════════════════════════════╗\n");
        sb.append("  ║  ").append(ConsoleRenderer.padRight("⚠ 需要人工处理", 48)).append("  ║\n");
        sb.append("  ╚══════════════════════════════════════════════════╝\n");
        sb.append("\n");
        if (result.verificationSummary() != null && !result.verificationSummary().isBlank()) {
            sb.append("  ").append(result.verificationSummary()).append("\n");
        }
        if (result.actionExecuted() != null && !result.actionExecuted().isBlank()) {
            sb.append("  已执行：").append(result.actionExecuted()).append("\n");
        }
        sb.append("\n");
        if (result.nextAction() != null && !result.nextAction().isBlank()) {
            sb.append("  → ").append(result.nextAction()).append("\n");
        }
        sb.append("\n");
        sb.append("  Incident：").append(result.incidentId())
            .append("  |  详情：/ops inspect ").append(result.incidentId()).append("\n");
        sb.append("\n");
        return sb.toString();
    }
}
