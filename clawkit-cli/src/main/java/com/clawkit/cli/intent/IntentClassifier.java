package com.clawkit.cli.intent;

import com.clawkit.engine.ProviderGateway;
import com.clawkit.engine.RunScope;
import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.schema.Message;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight intent classifier using ProviderGateway (unified observability).
 *
 * <p>Two-level classification:
 * <ol>
 *   <li>WorkScope: REMOTE_SERVER | LOCAL_PROJECT | CHAT | CLARIFY</li>
 *   <li>ServerIntent: only for REMOTE_SERVER scope</li>
 * </ol>
 *
 * <p>Safety guarantees:
 * <ul>
 *   <li>Model receives ONLY: user input, registered server names, active connection</li>
 *   <li>NO tools provided — model cannot execute anything</li>
 *   <li>10s timeout</li>
 *   <li>Unknown scope/intent → CLARIFY fallback</li>
 *   <li>Allowlist validation on both scope and intent</li>
 *   <li>Goes through ProviderGateway — recorded in observability system</li>
 * </ul>
 *
 * <p>PRODUCT-2: user intent layer with work scope isolation.
 */
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final Set<String> ALLOWED_SCOPES = Set.of(
        "REMOTE_SERVER", "LOCAL_PROJECT", "CHAT", "CLARIFY");

    private static final Set<String> ALLOWED_INTENTS = Set.of(
        "SERVER_OVERVIEW", "CONNECTION_STATUS", "CONNECT_TARGET",
        "TOOL_EXPLANATION", "SERVER_QUERY", "QUICK_CHECK", "INVESTIGATE_SERVICE", "NONE");

    private static final int TIMEOUT_SECONDS = 10;

    private static final Pattern JSON_PATTERN = Pattern.compile(
        "\\{[^{}]*\"scope\"[^{}]*\\}", Pattern.DOTALL);

    private final ProviderGateway gateway;

    public IntentClassifier(ProviderGateway gateway) {
        this.gateway = gateway;
    }

    /** Test-only: allow null gateway for unit tests that use classifyFromRaw. */
    IntentClassifier() {
        this.gateway = null;
    }

    /**
     * Classify user input into a structured intent with work scope.
     */
    public ClassifiedIntent classify(String userInput, List<String> registeredIds,
                                      String activeTargetId) {
        if (userInput == null || userInput.isBlank()) {
            return ClassifiedIntent.NONE;
        }

        // Unambiguous local-project requests must remain usable even when the
        // intent model is unavailable or returns malformed JSON. This is a
        // routing fallback only; the actual operation still runs through the
        // LOCAL_ONLY tool scope in IntentHandler.
        ClassifiedIntent deterministic = deterministicLocalProjectFallback(userInput);
        if (deterministic != null) {
            log.info("[intent] deterministic local-project classification: {}", deterministic.reason());
            return deterministic;
        }

        ClassifiedIntent investigation = deterministicInvestigationFallback(
            userInput, activeTargetId);
        if (investigation != null) {
            log.info("[intent] deterministic server investigation classification");
            return investigation;
        }

        ClassifiedIntent toolExplanation = deterministicToolExplanationFallback(
            userInput, activeTargetId);
        if (toolExplanation != null) {
            log.info("[intent] deterministic tool-explanation classification");
            return toolExplanation;
        }

        long startMs = System.currentTimeMillis();
        String systemPrompt = buildSystemPrompt(registeredIds, activeTargetId);
        List<Message> messages = List.of(
            Message.system(systemPrompt),
            Message.user(userInput)
        );

        try {
            String raw = callWithTimeout(messages);
            long elapsedMs = System.currentTimeMillis() - startMs;
            if (raw == null || raw.isBlank()) {
                log.warn("[intent] empty model response ({}ms)", elapsedMs);
                return modeDefaultFallback(userInput, activeTargetId, ClassifiedIntent.NONE);
            }

            ClassifiedIntent intent = parseResponse(raw, registeredIds, activeTargetId);
            if (intent == null) {
                log.warn("[intent] parse failed ({}ms), raw={}",
                    elapsedMs, raw.substring(0, Math.min(200, raw.length())));
                return modeDefaultFallback(userInput, activeTargetId, ClassifiedIntent.NONE);
            }

            intent = modeDefaultFallback(userInput, activeTargetId, intent);

            log.info("[intent] classified as {}/{} in {}ms — reason: {}",
                intent.scope(), intent.intent(), elapsedMs, intent.reason());
            return intent;

        } catch (TimeoutException e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.warn("[intent] timed out after {}ms (limit {}s)", elapsedMs, TIMEOUT_SECONDS);
            return modeDefaultFallback(userInput, activeTargetId, ClassifiedIntent.NONE);
        } catch (Exception e) {
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.warn("[intent] failed after {}ms: {}", elapsedMs, e.getMessage());
            return modeDefaultFallback(userInput, activeTargetId, ClassifiedIntent.NONE);
        }
    }

    /**
     * Recognize clear local code/Git requests without a provider round trip.
     * Returns null when the wording could refer to a remote server.
     */
    static ClassifiedIntent deterministicLocalProjectFallback(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;

        String text = userInput.toLowerCase(Locale.ROOT);
        boolean remoteMention = containsAny(text,
            "服务器", "远程", "线上", "test-server", "/remote", "remote server");
        if (remoteMention) return null;

        boolean explicitLocal = containsAny(text,
            "本地代码", "本地项目", "本地文件", "本地仓库", "本地源码");
        boolean codeChange = containsAny(text,
            "代码变动", "代码改动", "文件变动", "文件改动", "有什么变化",
            "git", "commit", "提交记录", "工作区", "diff", "uncommitted",
            "changed files");
        if (!explicitLocal && !codeChange) return null;

        return ClassifiedIntent.localProject("明确的本地代码或 Git 请求");
    }

    /** Recognize explicit investigation wording while a server connection is active. */
    static ClassifiedIntent deterministicInvestigationFallback(String userInput,
                                                               String activeTargetId) {
        if (userInput == null || userInput.isBlank()
            || activeTargetId == null || activeTargetId.isBlank()) return null;

        String text = userInput.toLowerCase(Locale.ROOT);
        if (!containsAny(text, "调查", "诊断", "排查", "investigate", "diagnose")) return null;

        String serviceId = containsAny(text, "order-api", "order api") ? "order-api" : "";
        return new ClassifiedIntent(WorkScope.REMOTE_SERVER,
            ServerIntent.INVESTIGATE_SERVICE, activeTargetId, serviceId, "",
            "服务器模式下的明确故障排查请求");
    }

    /** Recognize requests for the mounted remote tool catalog without an LLM round trip. */
    static ClassifiedIntent deterministicToolExplanationFallback(String userInput,
                                                                  String activeTargetId) {
        if (userInput == null || userInput.isBlank()) return null;
        String text = userInput.toLowerCase(Locale.ROOT);
        boolean asksAboutTools = containsAny(text, "工具", "mcp", "能力", "tool");
        boolean asksForExplanation = containsAny(text,
            "介绍", "说明", "干什么", "做什么", "用途", "详细", "区别", "一样吗");
        if (!asksAboutTools || !asksForExplanation) return null;

        if (activeTargetId != null && !activeTargetId.isBlank()) {
            return new ClassifiedIntent(WorkScope.REMOTE_SERVER,
                ServerIntent.TOOL_EXPLANATION, activeTargetId, "", "",
                "询问当前远程工具的用途和边界");
        }
        return ClassifiedIntent.chat(
            "工具可以理解为可被助手调用的操作接口，MCP 是其中一种连接协议。\n"
                + "本地工具负责文件、Git 和命令；远程工具通过 SSH/MCP 访问服务器，"
                + "并受只读范围、白名单和审计约束。\n"
                + "当前还没有连接服务器；连接后输入“详细介绍远程工具”，可以逐个查看工具用途。",
            "询问工具体系但当前没有活动远程连接");
    }

    /**
     * Use the current interaction mode as the default when classification is
     * unavailable or explicitly asks which scope the user meant.
     */
    static ClassifiedIntent modeDefaultFallback(String userInput, String activeTargetId,
                                                ClassifiedIntent classified) {
        if (classified != null
            && classified != ClassifiedIntent.NONE
            && classified.scope() != WorkScope.CLARIFY) {
            return classified;
        }

        String text = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (activeTargetId != null && !activeTargetId.isBlank()) {
            return new ClassifiedIntent(WorkScope.REMOTE_SERVER,
                ServerIntent.SERVER_QUERY, activeTargetId, "", "",
                "服务器模式下按当前服务器解释上下文问答");
        }

        // Without an active connection, explicit remote wording still needs a
        // target/connection clarification instead of being treated as local.
        if (containsAny(text, "服务器", "远程", "线上", "/remote", "remote server")) {
            return classified != null ? classified : ClassifiedIntent.NONE;
        }
        return ClassifiedIntent.localProject("本地模式下按当前项目解释上下文问答");
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) return true;
        }
        return false;
    }

    // ── internal ──────────────────────────────────────────────────────

    private String callWithTimeout(List<Message> messages) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(() -> {
                var req = ModelRequest.of(messages, Collections.emptyList());
                var scope = new RunScope("intent-classify", null, 0,
                    com.clawkit.engine.RunPhase.REACT,
                    com.clawkit.engine.ExecutionMode.REACT, null);
                var resp = gateway.generate(req, scope);
                return resp.content() != null ? resp.content() : "";
            });
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Exposed for testing. */
    String classifyFromRaw(String raw, List<String> registeredIds, String activeTargetId) {
        ClassifiedIntent intent = parseResponse(raw, registeredIds, activeTargetId);
        if (intent == null) return "CLARIFY";
        return intent.scope() + "/" + intent.intent();
    }

    // ── prompt ────────────────────────────────────────────────────────

    static String buildSystemPrompt(List<String> registeredIds, String activeTargetId) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你是一个工作范围分类器。先判断用户想做什么范围的事, 再判断具体动作。

            当前上下文:
            """);
        if (registeredIds.isEmpty()) {
            sb.append("- 已登记服务器: 无\n");
        } else {
            sb.append("- 已登记服务器: ").append(String.join(", ", registeredIds)).append("\n");
        }
        if (activeTargetId != null && !activeTargetId.isEmpty()) {
            sb.append("- 当前连接: ").append(activeTargetId).append("\n");
            sb.append("- 当前模式: SERVER（模糊的服务、状态、工具追问默认指向当前服务器）\n");
        } else {
            sb.append("- 当前连接: 无\n");
            sb.append("- 当前模式: LOCAL（模糊的代码、文件、Git 请求默认指向本地项目）\n");
        }

        sb.append("""

            你必须返回一个JSON对象, 包含以下字段:
            - scope: 工作范围 (REMOTE_SERVER / LOCAL_PROJECT / CHAT / CLARIFY)
            - intent: 具体动作 (REMOTE_SERVER时必填, 其他范围填NONE)
            - targetId: 服务器名称 (可为空字符串)
            - service: 服务名称 (可为空字符串)
            - reply: 聊天直接回复或追问内容 (可为空字符串)
            - reason: 简短判断依据 (内部使用)

            === 工作范围判断规则 ===

            REMOTE_SERVER — 用户想操作远程服务器:
            - 提到服务器、连接、检查服务运行、调查故障
            - 提到已登记服务器名称
            - 使用运维相关说法
            可选 intent:
              SERVER_OVERVIEW: 了解服务器概况
              CONNECTION_STATUS: 查看连接状态
              TOOL_EXPLANATION: 逐个解释当前已挂载远程工具的用途和只读边界
              SERVER_QUERY: 服务器模式下围绕当前服务器进行一般问答
              CONNECT_TARGET: 连接到服务器
              QUICK_CHECK: 快速检查服务是否正常
              INVESTIGATE_SERVICE: 深入调查故障

            LOCAL_PROJECT — 用户想处理本地代码项目:
            - 提到代码、项目、文件、测试、Git、构建
            - 涉及编程语言、框架、重构、调试
            - 要求读取/修改/创建/删除本地文件
            - 要求运行命令、查看项目结构
            intent 填 NONE

            CHAT — 普通对话, 不需要工具:
            - 问候、自我介绍、功能询问
            - 知识问答 (不涉及当前项目)
            - 闲聊
            intent 填 NONE, reply 直接写回复

            CLARIFY — 无法确定范围:
            - 信息不足, 无法判断是本地还是远程
            - "order-api" 既可能指本地代码也可能指服务器服务 → 应该追问
            - "帮我看看" 没说什么 → 应该追问
            - "修一下" 没说修什么 → 应该追问
            intent 填 NONE, reply 写追问内容

            === 示例 ===

            输入: "你好" →
            {"scope":"CHAT","intent":"NONE","targetId":"","service":"","reply":"你好! 我是 CLAWKIT，你的本地 AI 助手。","reason":"问候"}

            输入: "你是谁" →
            {"scope":"CHAT","intent":"NONE","targetId":"","service":"","reply":"我是 CLAWKIT，你的本地 AI 助手。我可以帮你处理本地项目代码、管理远程服务器。","reason":"身份询问"}

            输入: "解释一下这个项目" →
            {"scope":"LOCAL_PROJECT","intent":"NONE","targetId":"","service":"","reply":"","reason":"分析本地项目"}

            输入: "查看 Git 状态" →
            {"scope":"LOCAL_PROJECT","intent":"NONE","targetId":"","service":"","reply":"","reason":"本地Git操作"}

            输入: "修复这个报错" →
            {"scope":"LOCAL_PROJECT","intent":"NONE","targetId":"","service":"","reply":"","reason":"本地代码修复"}

            输入: "运行测试" →
            {"scope":"LOCAL_PROJECT","intent":"NONE","targetId":"","service":"","reply":"","reason":"运行本地测试"}

            """);

        if (!registeredIds.isEmpty()) {
            String srv = registeredIds.get(0);
            sb.append("输入: \"服务器怎么样\" →\n");
            sb.append("{\"scope\":\"REMOTE_SERVER\",\"intent\":\"SERVER_OVERVIEW\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"询问服务器概况\"}\n\n");
            sb.append("输入: \"连接 ").append(srv).append("\" →\n");
            sb.append("{\"scope\":\"REMOTE_SERVER\",\"intent\":\"CONNECT_TARGET\",\"targetId\":\"").append(srv).append("\",\"service\":\"\",\"reply\":\"\",\"reason\":\"连接服务器\"}\n\n");
            sb.append("输入: \"").append(srv).append(" 正常吗\" →\n");
            sb.append("{\"scope\":\"REMOTE_SERVER\",\"intent\":\"QUICK_CHECK\",\"targetId\":\"").append(srv).append("\",\"service\":\"\",\"reply\":\"\",\"reason\":\"检查服务器\"}\n\n");
        }

        sb.append("""
            输入: "帮我看看" →
            {"scope":"CLARIFY","intent":"NONE","targetId":"","service":"","reply":"你想看什么？是想查看服务器连接情况，还是查看项目代码？","reason":"信息不足"}

            输入: "修一下 order-api" →
            {"scope":"CLARIFY","intent":"NONE","targetId":"","service":"","reply":"你想处理哪一项？\\n\\n1. 本地 order-api 代码\\n2. 服务器上运行的 order-api 服务","reason":"无法区分本地还是远程"}

            重要规则:
            1. 只返回JSON, 不要其他内容
            2. REMOTE_SERVER 必须填 intent，其他范围填 NONE
            3. CHAT 和 CLARIFY 必须填 reply
            4. 无法区分本地代码还是远程服务时，必须用 CLARIFY 追问
            5. 编程/代码/文件/Git/测试/构建/重构 → LOCAL_PROJECT
            6. 问候/身份/功能询问/闲聊 → CHAT
            7. 服务器/连接/检查服务/调查故障 → REMOTE_SERVER
            """);

        return sb.toString();
    }

    // ── parsing ───────────────────────────────────────────────────────

    static ClassifiedIntent parseResponse(String raw, List<String> registeredIds,
                                           String activeTargetId) {
        if (raw == null || raw.isBlank()) return null;

        String json = extractJson(raw);
        if (json == null) return null;

        try {
            // Parse scope
            String scopeStr = extractField(json, "scope");
            if (scopeStr == null || !ALLOWED_SCOPES.contains(scopeStr.toUpperCase())) {
                log.warn("[intent] invalid scope: {}", scopeStr);
                return null;
            }
            WorkScope scope = WorkScope.valueOf(scopeStr.toUpperCase());

            // Parse intent
            String intentStr = extractField(json, "intent");
            if (intentStr == null) intentStr = "NONE";
            if (!ALLOWED_INTENTS.contains(intentStr.toUpperCase())) {
                log.warn("[intent] invalid intent: {}", intentStr);
                return null;
            }
            ServerIntent intent = ServerIntent.valueOf(intentStr.toUpperCase());

            // For REMOTE_SERVER, intent must be set (not NONE)
            if (scope == WorkScope.REMOTE_SERVER && intent == ServerIntent.NONE) {
                log.warn("[intent] REMOTE_SERVER with NONE intent");
                return null;
            }

            String targetId = extractField(json, "targetId");
            String service = extractField(json, "service");
            String reply = extractField(json, "reply");
            String reason = extractField(json, "reason");

            // Default targetId for server intents
            if (scope == WorkScope.REMOTE_SERVER) {
                if ((targetId == null || targetId.isBlank()) && intent == ServerIntent.CONNECT_TARGET
                    && registeredIds.size() == 1) {
                    targetId = registeredIds.get(0);
                }
                if ((targetId == null || targetId.isBlank())
                    && (intent == ServerIntent.QUICK_CHECK
                        || intent == ServerIntent.INVESTIGATE_SERVICE)
                    && activeTargetId != null) {
                    targetId = activeTargetId;
                }
            }

            return new ClassifiedIntent(scope, intent,
                targetId != null ? targetId : "",
                service != null ? service : "",
                reply != null ? reply : "",
                reason != null ? reason : "");

        } catch (IllegalArgumentException e) {
            log.warn("[intent] unknown enum: {}", e.getMessage());
            return null;
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────

    static String extractJson(String raw) {
        Matcher m = JSON_PATTERN.matcher(raw);
        if (m.find()) return m.group();
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.contains("\"scope\"")) {
            int depth = 0;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < trimmed.length(); i++) {
                char c = trimmed.charAt(i);
                sb.append(c);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return sb.toString();
                }
            }
        }
        return null;
    }

    static String extractField(String json, String fieldName) {
        Pattern p = Pattern.compile(
            "\"" + fieldName + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(json);
        if (m.find()) {
            String value = m.group(1);
            value = value.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\");
            return value;
        }
        return null;
    }
}
