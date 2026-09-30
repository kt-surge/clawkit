package com.clawkit.cli.ops;

import com.clawkit.cli.ConsoleRenderer;
import com.clawkit.cli.remote.RemoteTargetStore;
import com.clawkit.ops.delivery.IncidentSummary;
import com.clawkit.ops.delivery.InvestigationRequest;
import com.clawkit.ops.delivery.InvestigationView;
import com.clawkit.ops.delivery.OpsInvestigationFacade;
import com.clawkit.ops.loop.automation.ObservedSignal;
import com.clawkit.ops.loop.autonomy.FixtureShadowEvaluationRunner;
import com.clawkit.ops.loop.autonomy.ShadowReviewDecision;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code /ops} slash commands using deterministic parsing.
 *
 * <p>OPS-PRODUCT-LOOP-1 §12.
 */
public class OpsCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(OpsCommandHandler.class);

    private final OpsInvestigationFacade facade;
    private final RemoteTargetStore targetStore;
    private final JLineInvestigationInteraction interaction;
    private final FixtureAutomationController fixtureAutomation;

    public OpsCommandHandler(OpsInvestigationFacade facade, RemoteTargetStore targetStore,
                              JLineInvestigationInteraction interaction) {
        this(facade, targetStore, interaction, new FixtureAutomationController(
            Path.of(System.getProperty("user.home"), ".clawkit", "fixtures", "ops-3a-observe-only")));
    }

    OpsCommandHandler(OpsInvestigationFacade facade, RemoteTargetStore targetStore,
                      JLineInvestigationInteraction interaction,
                      FixtureAutomationController fixtureAutomation) {
        this.facade = facade;
        this.targetStore = targetStore;
        this.interaction = interaction;
        this.fixtureAutomation = fixtureAutomation;
    }

    /** Handle a /ops command. Returns true if recognized. */
    public boolean handle(String arguments) {
        var cmd = OpsCommandParser.parse(arguments);
        try {
            return switch (cmd.subCommand()) {
                case INVESTIGATE -> { cmdInvestigate(cmd); yield true; }
                case RECENT -> { cmdRecent(cmd.recentLimit()); yield true; }
                case INSPECT -> { cmdInspect(cmd.incidentId()); yield true; }
                case CONTINUE -> { cmdContinue(cmd.incidentId()); yield true; }
                case FEEDBACK -> { cmdFeedback(cmd.question()); yield true; }
                case DOGFOOD_STATUS -> { cmdDogfoodStatus(); yield true; }
                case OBSERVE_FIXTURE -> { cmdObserveFixture(cmd.question()); yield true; }
                case HELP -> { cmdHelp(); yield true; }
                case UNKNOWN -> { cmdHelp(); yield true; }
            };
        } catch (Exception e) {
            println("  [ERROR] " + e.getMessage());
            log.warn("[ops-cmd] failed: {}", e.getMessage());
            return true;
        }
    }

    private void cmdInvestigate(OpsCommandParser.ParsedCommand cmd) {
        String targetId = cmd.targetId();
        String serviceId = cmd.serviceId();

        if (targetId == null || targetId.isEmpty()) {
            println("  请指定要调查的目标服务器。");
            println("  用法: /ops investigate <targetId> [serviceId] [问题描述]");
            println("  已登记的目标: " + String.join(", ", targetStore.list()));
            return;
        }

        if (!targetStore.exists(targetId)) {
            println("  未找到目标: " + targetId);
            List<String> registered = targetStore.list();
            if (!registered.isEmpty()) {
                println("  已登记的目标: " + String.join(", ", registered));
            }
            println("  使用 /remote add --from-ssh <alias> 注册新目标");
            return;
        }

        if (!OpsCommandParser.isAllowedService(serviceId)) {
            println("  不支持的服务: " + serviceId);
            println("  当前仅支持: order-api");
            return;
        }

        println("  开始调查 " + targetId + " 上的 " + serviceId + "……\n");

        var request = new InvestigationRequest(targetId, serviceId, cmd.question());
        interaction.beginDogfoodTask(targetId);
        facade.investigateAndMaybeRepair(request, interaction);
    }

    private void cmdRecent(int limit) {
        List<IncidentSummary> incidents = facade.recent(limit);
        if (incidents.isEmpty()) {
            println("  暂无调查记录。");
            println("  使用 /ops investigate <target> <service> 开始调查。");
            return;
        }
        System.out.println();
        for (IncidentSummary s : incidents) {
            String time = s.updatedAt() != null
                ? s.updatedAt().toString().replace("T", " ").substring(0, 16) : "";
            String target = s.targetId() != null ? s.targetId() : "?";
            String svc = s.serviceId() != null ? " · " + s.serviceId() : "";
            String statusCn = com.clawkit.ops.delivery.OpsInvestigationFacade.statusChinese(s.status());
            String shortId = s.incidentId().length() > 28
                ? s.incidentId().substring(0, 28) : s.incidentId();
            println("  [" + statusCn + "] " + shortId + "  " + target + svc + "  " + time);
            if (s.briefSummary() != null && !s.briefSummary().isBlank()) {
                println("    " + s.briefSummary());
            }
        }
        System.out.println();
    }

    private void cmdInspect(String incidentId) {
        if (incidentId == null || incidentId.isEmpty()) {
            println("  用法: /ops inspect <incidentId>");
            println("  使用 /ops recent 查看最近的调查记录。");
            return;
        }

        InvestigationView view = facade.inspect(incidentId);
        if (view == null) {
            println("  未找到调查记录: " + incidentId);
            return;
        }

        System.out.println();
        println("  Incident:    " + view.incidentId());
        println("  Target:      " + view.targetId());
        if (view.serviceId() != null && !view.serviceId().isEmpty()) {
            println("  Service:     " + view.serviceId());
        }
        println("  Status:      " + OpsInvestigationFacade.statusChinese(view.status()));
        println("  Created:     " + formatTime(view.createdAt()));
        println("  Updated:     " + formatTime(view.updatedAt()));

        if (view.diagnosis() != null && !view.diagnosis().isBlank()
            && !"未完成诊断".equals(view.diagnosis())) {
            println("  Diagnosis:   " + view.diagnosis());
        }
        if (view.recommendation() != null && !view.recommendation().isBlank()) {
            println("  Recommend:   " + view.recommendation());
        }
        if (view.actionExecuted() != null && !view.actionExecuted().isBlank()) {
            println("  Action:      " + view.actionExecuted());
        }
        if (view.verificationSummary() != null && !view.verificationSummary().isBlank()) {
            println("  Verify:      " + view.verificationSummary());
        }
        if (view.nextAction() != null && !view.nextAction().isBlank()) {
            println("  Next:        " + view.nextAction());
        }
        System.out.println();
        println("  Evidence:    ~/.clawkit/" + view.evidenceDirectory());
        System.out.println();
    }

    private void cmdContinue(String incidentId) {
        if (incidentId == null || incidentId.isEmpty()) {
            println("  用法: /ops continue <incidentId>");
            println("  使用 /ops recent 查看可继续的调查记录。");
            return;
        }

        println("  继续调查 " + incidentId + "……");
        facade.continueIncident(incidentId, interaction);
    }

    private void cmdFeedback(String payload) {
        if (payload == null) {
            println("  用法: /ops feedback <incidentId> <yes|no> <yes|no> <HIGH|MEDIUM|LOW> [说明]");
            return;
        }
        String[] parts = payload.split("\\s+", 5);
        if (parts.length < 4 || !isYesNo(parts[1]) || !isYesNo(parts[2]) || !isPriority(parts[3])) {
            println("  用法: /ops feedback <incidentId> <yes|no> <yes|no> <HIGH|MEDIUM|LOW> [说明]");
            return;
        }
        String description = parts.length == 5 ? parts[4] : "";
        interaction.logDogfoodFeedback(parts[0], yes(parts[1]), yes(parts[2]), description,
            parts[3].toUpperCase(java.util.Locale.ROOT));
        println("  已记录 dogfood 反馈（已脱敏）。");
    }

    private void cmdDogfoodStatus() throws java.io.IOException {
        var summary = interaction == null ? DogfoodLogger.Summary.missing() : interaction.dogfoodSummary();
        if (!summary.logPresent()) {
            println("  暂无本地 dogfood 记录。真实远程保持只读；可先完成只读调查或手动 Fixture Shadow review。");
            return;
        }
        println("  Dogfood 采集摘要（不显示目标、Incident 或反馈正文）：");
        println("    有效 / 损坏：" + summary.validEvents() + " / " + summary.invalidEvents());
        println("    记录天数：" + summary.recordedDays() + "（" + (summary.consecutiveDays() ? "连续" : "不连续") + "）");
        println("    调查 / 反馈 / A3 review：" + summary.taskEvents() + " / " + summary.frictionEvents()
            + " / " + summary.shadowReviews());
        println("    A3 会同意 / 会拒绝 / 证据不足：" + summary.wouldApprove() + " / "
            + summary.wouldReject() + " / " + summary.needsMoreEvidence());
        println(summary.hasSevenConsecutiveDays()
            ? "    时间覆盖达到 7 个连续记录日；仍需人工审计原始脱敏记录，不能自动提升到 A4。"
            : "    尚未达到 7 个连续记录日；不能作为 A3 dogfood 或 A4 评审证据。");
    }

    private void cmdObserveFixture(String action) throws java.io.IOException {
        String normalized = action == null ? "status" : action.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("shadow-review ")) {
            String choice = normalized.substring("shadow-review ".length()).strip();
            ShadowReviewDecision reviewDecision = switch (choice) {
                case "approve" -> ShadowReviewDecision.WOULD_APPROVE;
                case "reject" -> ShadowReviewDecision.WOULD_REJECT;
                case "defer" -> ShadowReviewDecision.NEEDS_MORE_EVIDENCE;
                default -> null;
            };
            if (reviewDecision == null) {
                println("  用法: /ops observe fixture shadow-review <approve|reject|defer>");
                return;
            }
            var recorded = fixtureAutomation.reviewLatestShadow(reviewDecision);
            var review = recorded.review();
            if (interaction != null) interaction.logDogfoodShadowReview(recorded);
            println(recorded.created()
                ? "  已记录 A3 人工反事实选择：" + review.reviewerDecision() + "。"
                : "  已存在相同 A3 人工反事实选择；未重复写入 dogfood。");
            println("    Decision: " + review.decisionId());
            println("    Side effects: " + review.sideEffectCalls() + "（未创建审批或修复）。");
            return;
        }
        switch (normalized) {
            case "start" -> {
                fixtureAutomation.start();
                println("  已启动 OPS-3A Fixture observe-only loop。仅生成 fixture:// 证据，不连接远程服务器，不调用 Provider，不执行修复。");
            }
            case "pause" -> {
                fixtureAutomation.pause();
                println("  Fixture observe-only loop 已暂停；暂停后不会开始新的 Discovery。");
            }
            case "resume" -> {
                fixtureAutomation.resume();
                println("  Fixture observe-only loop 已恢复。");
            }
            case "stop" -> {
                fixtureAutomation.stop();
                println("  Fixture observe-only loop 已停止；本地状态和证据保留供检查。");
            }
            case "snapshot" -> {
                var snapshot = fixtureAutomation.snapshot();
                println("  已生成 Fixture 证据快照: " + snapshot.snapshotId());
                println("    Target/events: " + snapshot.targetId() + "/" + snapshot.timelineEvents());
                println("    Directory: " + snapshot.directory());
                println("    四份文件已由 manifest 绑定；可导入本地 Observe-only Console 回放。");
            }
            case "soak" -> {
                var result = fixtureAutomation.acceleratedSoak();
                var report = result.report();
                println("  已完成 72 逻辑小时 Fixture 加速 soak（不是自然墙钟 72 小时）。");
                println("    Requested/started: " + report.counts().requested() + "/" + report.counts().started());
                println("    Completed/merged/failed: " + report.counts().completed() + "/"
                    + report.counts().merged() + "/" + report.counts().failed());
                println("    Provider: " + report.providerConsumed() + "/" + report.providerLimit());
                println("    Report: " + result.reportPath());
                println("    Snapshot: " + result.snapshot().directory());
            }
            case "shadow" -> {
                var result = fixtureAutomation.shadowReplay();
                var decision = result.recordedDecision().decision();
                println("  已完成 A3 Fixture Shadow 回放：仅记录反事实结论，不执行修复。");
                println("    Snapshot: " + result.snapshot().snapshotId());
                println("    Policy: " + result.policy().policyHash());
                println("    Decision: " + decision.decisionId() + " · " + decision.outcome());
                println("    Reasons: " + String.join(",", decision.reasonCodes()));
                println("    Side effects: " + decision.sideEffectCalls());
                println("    Directory: " + result.directory());
            }
            case "shadow-eval" -> {
                var result = fixtureAutomation.shadowEvaluation();
                var report = result.report();
                var counts = report.counts();
                println("  已完成 A3 Fixture 100 案例契约评测：仅本地决策，不执行修复。");
                println("    Eligible/ask/rejected/expired: " + counts.eligibleShadow() + "/"
                    + counts.askRequired() + "/" + counts.rejected() + "/" + counts.expired());
                println("    Side effects: " + report.sideEffectCalls());
                println("    Report: " + result.directory().resolve(FixtureShadowEvaluationRunner.REPORT_FILE));
            }
            case "app-down" -> {
                fixtureAutomation.setSignal(ObservedSignal.APP_DOWN);
                println("  Fixture 信号已切换为 APP_DOWN。");
            }
            case "healthy" -> {
                fixtureAutomation.setSignal(ObservedSignal.HEALTHY);
                println("  Fixture 信号已切换为 HEALTHY；下一次观察可关闭对应 ACTIVE Incident。");
            }
            case "unknown" -> {
                fixtureAutomation.setSignal(ObservedSignal.UNKNOWN);
                println("  Fixture 信号已切换为 UNKNOWN；现有 ACTIVE Incident 不会被关闭。");
            }
            case "status" -> printFixtureStatus(fixtureAutomation.view());
            case "replay" -> printFixtureReplay();
            default -> printFixtureHelp();
        }
    }

    private void printFixtureReplay() throws java.io.IOException {
        var entries = fixtureAutomation.timeline(10);
        if (entries.isEmpty()) {
            println("  暂无可回放的 Fixture 观测。先执行 /ops observe fixture start。");
            return;
        }
        println("  Fixture observe-only 回放（最多最近 10 条，未连接远程服务器）：");
        for (var entry : entries) {
            String at = entry.at().toString().replace("T", " ").replace("Z", " UTC");
            println("    " + at + "  " + entry.type().replace("OBSERVATION_", "")
                + "  evidence=" + entry.evidenceRefs().size()
                + "  provider=" + entry.providerCalled());
        }
    }

    private void printFixtureStatus(FixtureAutomationController.View view) {
        if (!view.running()) {
            println("  Fixture observe-only loop 未启动。用法: /ops observe fixture start");
            return;
        }
        var status = view.status();
        var budget = view.budget();
        println("  Fixture observe-only loop 正在运行");
        println("    Signal: " + view.signal());
        println("    Requested/started: " + status.requested() + "/" + status.started());
        println("    Completed/merged/failed: " + status.completed() + "/" + status.merged() + "/" + status.failed());
        println("    Registry entries: " + view.incidentCount());
        println("    Discovery budget: " + budget.discoveryConsumed() + "/" + budget.discoveryLimit());
        println("    Provider budget: " + budget.providerConsumed() + "/" + budget.providerLimit() + " (固定为 0)");
        if (status.lastSkipReason() != null) println("    Last skip: " + status.lastSkipReason());
    }

    private void printFixtureHelp() {
        println("  /ops observe fixture <start|status|pause|resume|app-down|healthy|unknown|replay|stop|snapshot|soak|shadow|shadow-eval>");
        println("  /ops observe fixture shadow-review <approve|reject|defer>");
        println("      仅运行本地 Fixture A0；snapshot/soak/shadow/shadow-eval/review 需先 stop，不连接远程服务器，不执行修复。");
    }

    private static boolean isYesNo(String value) {
        return "yes".equalsIgnoreCase(value) || "no".equalsIgnoreCase(value);
    }

    private static boolean yes(String value) { return "yes".equalsIgnoreCase(value); }

    private static boolean isPriority(String value) {
        return "HIGH".equalsIgnoreCase(value) || "MEDIUM".equalsIgnoreCase(value)
            || "LOW".equalsIgnoreCase(value);
    }

    private void cmdHelp() {
        println("  /ops investigate <target> [service] [question]");
        println("      调查目标服务器上的服务状态");
        println("  /ops recent [limit]");
        println("      查看最近的调查记录（默认 10 条）");
        println("  /ops inspect <incidentId>");
        println("      查看调查详情");
        println("  /ops continue <incidentId>");
        println("      继续之前的调查");
        println("  /ops feedback <incidentId> <yes|no> <yes|no> <HIGH|MEDIUM|LOW> [说明]");
        println("      记录是否看懂、是否退回 SSH 与摩擦说明");
        println("  /ops dogfood [status]");
        println("      只读查看脱敏 dogfood 采集覆盖，不显示任务正文");
        println("  /ops observe fixture <start|status|pause|resume|app-down|healthy|unknown|replay|stop|snapshot|soak|shadow|shadow-eval>");
        println("  /ops observe fixture shadow-review <approve|reject|defer>");
        println("      运行仅限本地 Fixture 的持续只读观察闭环");
        println("");
        println("  Quick start:");
        println("    1. /remote connect <target>");
        println("    2. /ops investigate <target> order-api");
        println("    3. /ops recent");
        println("    4. /ops inspect <incidentId>");
        println("");
        println("  仅支持已登记的目标和 order-api 服务。");
        println("  调查过程先执行只读操作；如需修复会请求审批。");
        println("  Fixture observe-only 不连接已登记目标，也不会进入审批或修复。");
    }

    /** Release the local Fixture scheduler when the CLI exits. */
    public void close() {
        fixtureAutomation.close();
    }

    private static String formatTime(java.time.Instant instant) {
        if (instant == null) return "";
        return instant.toString().replace("T", " ").substring(0, 19);
    }

    private static void println(String text) {
        System.out.println(ConsoleRenderer.GRAY + text + ConsoleRenderer.RESET);
    }
}
