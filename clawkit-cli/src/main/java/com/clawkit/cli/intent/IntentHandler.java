package com.clawkit.cli.intent;

import com.clawkit.cli.ops.OpsCommandHandler;
import com.clawkit.cli.remote.RemoteCommandHandler;
import com.clawkit.cli.remote.RemoteConnectionService;
import com.clawkit.cli.remote.RemoteTargetStore;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.tools.RunToolScope;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches classified intents with tool scope isolation and engine lock.
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>REMOTE_SERVER → only remote read-only tools via engine.run(input, REMOTE_READ_ONLY)</li>
 *   <li>LOCAL_PROJECT → only local tools via engine.run(input, LOCAL_ONLY)</li>
 *   <li>CHAT → no tools via engine.run(prompt, NO_TOOLS) or direct reply</li>
 *   <li>CLARIFY → no tools, show clarification question</li>
 *   <li>All engine.run() calls protected by tryAcquire/release</li>
 *   <li>Tool scope is a parameter, not shared mutable state</li>
 * </ul>
 *
 * <p>PRODUCT-2: hardened intent dispatch.
 */
public class IntentHandler {

    private static final Logger log = LoggerFactory.getLogger(IntentHandler.class);

    private final RemoteTargetStore store;
    private final RemoteConnectionService service;
    private final AgentEngine engine;
    private final RemoteCommandHandler remoteCmd;
    private final OpsCommandHandler opsCmd;

    public IntentHandler(RemoteTargetStore store, RemoteConnectionService service,
                         AgentEngine engine, RemoteCommandHandler remoteCmd,
                         OpsCommandHandler opsCmd) {
        this.store = store;
        this.service = service;
        this.engine = engine;
        this.remoteCmd = remoteCmd;
        this.opsCmd = opsCmd;
    }

    public boolean handle(ClassifiedIntent intent, String userInput) {
        if (intent == null) {
            return handleClarify("我没能确定你想做什么，请换一种说法，或者输入 /help 查看可用操作。");
        }

        return switch (intent.scope()) {
            case REMOTE_SERVER -> handleRemoteServer(intent, userInput);
            case LOCAL_PROJECT -> handleLocalProject(userInput);
            case CHAT -> handleChat(intent, userInput);
            case CLARIFY -> handleClarify(intent.reply());
        };
    }

    // ── REMOTE_SERVER ─────────────────────────────────────────────────

    private boolean handleRemoteServer(ClassifiedIntent intent, String userInput) {
        return switch (intent.intent()) {
            case SERVER_OVERVIEW -> { remoteCmd.handle("status"); yield true; }
            case CONNECTION_STATUS -> { remoteCmd.handle("status"); yield true; }
            case TOOL_EXPLANATION -> { remoteCmd.handle("tools"); yield true; }
            case SERVER_QUERY -> { doServerQuery(intent, userInput); yield true; }
            case CONNECT_TARGET -> { doConnect(intent); yield true; }
            case QUICK_CHECK -> { doQuickCheck(intent); yield true; }
            case INVESTIGATE_SERVICE -> { doInvestigate(intent, userInput); yield true; }
            case NONE -> handleClarify("我没能确定你想对服务器做什么，请说具体一点。");
        };
    }

    private void doServerQuery(ClassifiedIntent intent, String userInput) {
        String active = service.activeTargetId();
        if (active == null || active.isBlank()) {
            handleClarify("当前是本地模式。请先连接服务器，再继续服务器问答。");
            return;
        }
        if (!engine.tryAcquire()) {
            System.out.println("  引擎正忙，请稍后再试。");
            return;
        }
        try {
            String prompt = buildServerQueryPrompt(active, userInput);
            String result = engine.run(prompt, RunToolScope.REMOTE_READ_ONLY);
            System.out.println();
            if (result != null && !result.isBlank()) System.out.println(result);
            System.out.println();
        } catch (Exception e) {
            System.out.println("  服务器问答失败: " + e.getMessage());
            log.warn("[intent] server query failed: {}", e.getMessage());
        } finally {
            engine.release();
        }
    }

    static String buildServerQueryPrompt(String activeTargetId, String userInput) {
        return "当前处于服务器模式，活动服务器是 " + activeTargetId + "。"
            + "请主要围绕这台服务器回答用户问题。只允许使用当前服务器的远程只读工具，"
            + "不要读取本地项目来猜测服务器状态；如果不需要工具，可以直接解释。"
            + "用户问题：" + userInput;
    }

    private void doConnect(ClassifiedIntent intent) {
        String targetId = intent.targetId();

        if (targetId == null || targetId.isBlank()) {
            List<String> ids = store.list();
            if (ids.isEmpty()) {
                System.out.println("  还没有登记服务器。");
                System.out.println("  输入 /remote add --from-ssh <SSH别名> 添加。");
            } else if (ids.size() == 1) {
                System.out.println("  要连接 " + ids.get(0) + " 吗？");
            } else {
                System.out.println("  你想连接哪台服务器？");
                System.out.println("  已登记: " + String.join(", ", ids));
            }
            return;
        }

        if (!store.exists(targetId)) {
            System.out.println("  没有找到这台服务器。");
            List<String> ids = store.list();
            if (!ids.isEmpty()) {
                System.out.println("  已登记服务器:");
                for (String id : ids) System.out.println("    - " + id);
            } else {
                System.out.println("  输入 /remote add --from-ssh <SSH别名> 添加。");
            }
            return;
        }

        String active = service.activeTargetId();
        if (active != null && active.equals(targetId)) {
            System.out.println("  已经连接到 " + targetId + "。");
            return;
        }

        System.out.println("  正在连接 " + targetId + "...");
        try {
            service.connect(targetId);
            System.out.println("  连接成功。");
            System.out.println("  已切换到服务器模式: " + targetId);
            System.out.println("  现在可以输入 [检查服务器] 查看服务状态。");
        } catch (IOException e) {
            System.out.println("  连接失败: 远端程序没有正常启动。");
            System.out.println("  下一步: 输入 /remote doctor " + targetId + " 查看详细原因。");
        }
    }

    private void doQuickCheck(ClassifiedIntent intent) {
        String targetId = intent.targetId();
        String active = service.activeTargetId();

        if (targetId == null || targetId.isBlank()) targetId = active;
        if (targetId == null || targetId.isBlank()) {
            List<String> ids = store.list();
            if (ids.isEmpty()) {
                System.out.println("  还没有登记服务器。");
            } else {
                System.out.println("  已登记: " + String.join(", ", ids));
                if (ids.size() == 1)
                    System.out.println("  请先输入 [连接 " + ids.get(0) + "] 进行连接。");
            }
            return;
        }

        if (active == null || !active.equals(targetId)) {
            System.out.println("  " + targetId + " 还没有连接。");
            System.out.println("  请先输入 [连接 " + targetId + "]。");
            return;
        }

        System.out.println("  正在检查服务状态...");
        if (!engine.tryAcquire()) {
            System.out.println("  引擎正忙，请稍后再试。");
            return;
        }
        QuickCheckStreamRenderer stream = new QuickCheckStreamRenderer(System.out);
        Consumer<String> streamListener = stream::accept;
        engine.addOnTokenListener(streamListener);
        try {
            String prompt = buildQuickCheckPrompt(targetId, intent.service());
            String result = engine.run(prompt, RunToolScope.REMOTE_READ_ONLY);
            if (stream.started()) {
                stream.finish();
            } else {
                String cleaned = QuickCheckStreamRenderer.cleanFinalResult(result);
                System.out.println();
                if (cleaned != null && !cleaned.isBlank()) {
                    System.out.println(cleaned);
                } else {
                    System.out.println("  检查完成，但没有收到有效报告，请重试。");
                }
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println("  检查失败: 无法读取服务器状态。");
            System.out.println("  下一步: 输入 /verbose on 查看详细原因。");
            log.warn("[intent] quick check failed: {}", e.getMessage());
        } finally {
            engine.removeOnTokenListener(streamListener);
            engine.release();
        }
    }

    static String buildQuickCheckPrompt(String targetId, String service) {
        String serviceHint = service != null && !service.isBlank()
            ? " 重点关注: " + service : "";
        return "快速检查服务器 " + targetId + " 的服务运行情况。"
            + serviceHint
            + " 只使用远程只读工具。"
            + "调用工具的轮次不要输出解释、分析或阶段总结，只返回工具调用。"
            + "最终回答第一行必须原样写 `## 检查结果`，标记前不能有任何文字。"
            + "只输出一次最终报告，不要出现‘我已收集’、‘让我梳理’、‘我需要’等思考过程。"
            + "报告用简单中文，固定包含: 状态、目标、影响、结论、证据时间、证据、下一步。"
            + "关键信息最多4条，证据不足最多2条，证据引用最多3条，下一步最多3条，全文不超过25行。"
            + "保留工具结果中 remoteEvidence 的 observedAt 或 collectedAt，"
            + "并至少原样输出一个 run://.../tool/... 证据引用。"
            + "无法直接验证的项目必须明确写证据不足，不能根据间接信号宣称健康。";
    }

    private void doInvestigate(ClassifiedIntent intent, String userInput) {
        String targetId = intent.targetId();
        String active = service.activeTargetId();
        if (targetId == null || targetId.isBlank()) targetId = active;
        if (targetId == null || targetId.isBlank()) {
            System.out.println("  请指定要调查的服务器。");
            List<String> ids = store.list();
            if (!ids.isEmpty()) System.out.println("  已登记: " + String.join(", ", ids));
            return;
        }
        if (!store.exists(targetId)) {
            System.out.println("  未找到服务器: " + targetId);
            return;
        }
        String serviceId = intent.service() != null && !intent.service().isBlank()
            ? intent.service() : "order-api";
        opsCmd.handle(buildInvestigationCommand(targetId, serviceId, userInput));
    }

    static String buildInvestigationCommand(String targetId, String serviceId, String userInput) {
        String question = userInput == null ? "" : userInput.strip();
        return "investigate " + targetId + " " + serviceId
            + (question.isEmpty() ? "" : " " + question);
    }

    // ── LOCAL_PROJECT ─────────────────────────────────────────────────

    private boolean handleLocalProject(String userInput) {
        if (!engine.tryAcquire()) {
            System.out.println("  引擎正忙，请稍后再试。");
            return true;
        }
        try {
            String result = engine.run(userInput, RunToolScope.LOCAL_ONLY);
            System.out.println();
            if (result != null && !result.isBlank()) {
                System.out.println(result);
            }
            System.out.println();
        } catch (Exception e) {
            System.out.println("  处理失败: " + e.getMessage());
            log.warn("[intent] local project failed: {}", e.getMessage());
        } finally {
            engine.release();
        }
        return true;
    }

    // ── CHAT ──────────────────────────────────────────────────────────

    private boolean handleChat(ClassifiedIntent intent, String userInput) {
        String reply = intent.reply();
        if (reply != null && !reply.isBlank()) {
            System.out.println();
            System.out.println(reply);
            System.out.println();
            return true;
        }
        // Fallback: no-tools engine call with original user input
        if (!engine.tryAcquire()) {
            System.out.println("  引擎正忙，请稍后再试。");
            return true;
        }
        try {
            String prompt = "用简单中文简短回答: " + userInput;
            String result = engine.run(prompt, RunToolScope.NO_TOOLS);
            if (result != null && !result.isBlank()) {
                System.out.println();
                System.out.println(result);
                System.out.println();
            }
        } catch (Exception e) {
            log.warn("[intent] chat fallback failed: {}", e.getMessage());
        } finally {
            engine.release();
        }
        return true;
    }

    // ── CLARIFY ───────────────────────────────────────────────────────

    private boolean handleClarify(String question) {
        if (question != null && !question.isBlank()) {
            System.out.println();
            System.out.println(question);
            System.out.println();
        } else {
            System.out.println();
            System.out.println("  我没能确定你想做什么，请换一种说法，或者输入 /help 查看可用操作。");
            System.out.println();
        }
        return true;
    }
}
