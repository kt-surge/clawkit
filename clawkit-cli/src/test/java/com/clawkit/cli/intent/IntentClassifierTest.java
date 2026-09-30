package com.clawkit.cli.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentClassifierTest {

    // ── JSON extraction ───────────────────────────────────────────────

    @Test
    void shouldExtractJsonWithScopeField() {
        String json = IntentClassifier.extractJson(
            "{\"scope\":\"CHAT\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"你好\",\"reason\":\"问候\"}");
        assertThat(json).contains("\"scope\":\"CHAT\"");
    }

    @Test
    void shouldReturnNullForJsonWithoutScope() {
        assertThat(IntentClassifier.extractJson("{\"foo\":\"bar\"}")).isNull();
    }

    // ── Scope classification ──────────────────────────────────────────

    @Test
    void shouldParseChatScopeFromHello() {
        String raw = "{\"scope\":\"CHAT\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"你好!\",\"reason\":\"问候\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.CHAT);
        assertThat(result.intent()).isEqualTo(ServerIntent.NONE);
        assertThat(result.reply()).isEqualTo("你好!");
    }

    @Test
    void shouldParseLocalProjectScope() {
        String raw = "{\"scope\":\"LOCAL_PROJECT\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"查看Git\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.LOCAL_PROJECT);
        assertThat(result.intent()).isEqualTo(ServerIntent.NONE);
    }

    @Test
    void shouldParseRemoteServerScope() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"SERVER_OVERVIEW\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"询问服务器\"}";
        var result = IntentClassifier.parseResponse(raw, List.of("test-server"), null);
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(result.intent()).isEqualTo(ServerIntent.SERVER_OVERVIEW);
    }

    @Test
    void shouldParseQuickCheckWithService() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"QUICK_CHECK\",\"targetId\":\"test-server\",\"service\":\"order-api\",\"reply\":\"\",\"reason\":\"检查\"}";
        var result = IntentClassifier.parseResponse(raw, List.of("test-server"), "test-server");
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(result.intent()).isEqualTo(ServerIntent.QUICK_CHECK);
        assertThat(result.service()).isEqualTo("order-api");
    }

    @Test
    void shouldParseConnectTarget() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"CONNECT_TARGET\",\"targetId\":\"test-server\",\"service\":\"\",\"reply\":\"\",\"reason\":\"连接\"}";
        var result = IntentClassifier.parseResponse(raw, List.of("test-server"), null);
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(result.targetId()).isEqualTo("test-server");
    }

    @Test
    void shouldParseInvestigateService() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"INVESTIGATE_SERVICE\",\"targetId\":\"test-server\",\"service\":\"order-api\",\"reply\":\"\",\"reason\":\"调查\"}";
        var result = IntentClassifier.parseResponse(raw, List.of("test-server"), "test-server");
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(result.intent()).isEqualTo(ServerIntent.INVESTIGATE_SERVICE);
    }

    @Test
    void shouldParseClarifyScope() {
        String raw = "{\"scope\":\"CLARIFY\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"你想看什么?\",\"reason\":\"不清楚\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNotNull();
        assertThat(result.scope()).isEqualTo(WorkScope.CLARIFY);
        assertThat(result.reply()).isEqualTo("你想看什么?");
    }

    // ── Safety: scope validation ──────────────────────────────────────

    @Test
    void shouldRejectUnknownScope() {
        String raw = "{\"scope\":\"EXECUTE_CODE\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNull();
    }

    @Test
    void shouldRejectRemoteServerWithNoneIntent() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNull();
    }

    @Test
    void shouldRejectUnknownIntent() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"DELETE_ALL\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNull();
    }

    @Test
    void shouldRejectMissingScopeField() {
        String raw = "{\"intent\":\"SERVER_OVERVIEW\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var result = IntentClassifier.parseResponse(raw, List.of(), null);
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullForMalformedJson() {
        var result = IntentClassifier.parseResponse("not json", List.of(), null);
        assertThat(result).isNull();
    }

    @Test
    void shouldReturnNullForEmptyResponse() {
        var result = IntentClassifier.parseResponse("", List.of(), null);
        assertThat(result).isNull();
    }

    // ── Target defaulting ─────────────────────────────────────────────

    @Test
    void shouldDefaultTargetForQuickCheckWhenConnected() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"QUICK_CHECK\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var result = IntentClassifier.parseResponse(raw, List.of("srv"), "srv");
        assertThat(result).isNotNull();
        assertThat(result.targetId()).isEqualTo("srv");
    }

    @Test
    void shouldDefaultTargetWhenOnlyOneRegistered() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"CONNECT_TARGET\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var result = IntentClassifier.parseResponse(raw, List.of("my-server"), null);
        assertThat(result).isNotNull();
        assertThat(result.targetId()).isEqualTo("my-server");
    }

    // ── Prompt: no local paths ────────────────────────────────────────

    @Test
    void shouldNotIncludeLocalPathsInPrompt() {
        String prompt = IntentClassifier.buildSystemPrompt(List.of("srv"), null);
        assertThat(prompt).doesNotContain(".clawkit");
        assertThat(prompt).doesNotContain("CLAUDE.md");
        assertThat(prompt).doesNotContain("README");
        assertThat(prompt).doesNotContain("workdir");
    }

    @Test
    void shouldIncludeAllScopesInPrompt() {
        String prompt = IntentClassifier.buildSystemPrompt(List.of("srv"), null);
        assertThat(prompt).contains("REMOTE_SERVER");
        assertThat(prompt).contains("LOCAL_PROJECT");
        assertThat(prompt).contains("CHAT");
        assertThat(prompt).contains("CLARIFY");
    }

    @Test
    void shouldIncludeAllServerIntentsInPrompt() {
        String prompt = IntentClassifier.buildSystemPrompt(List.of("srv"), null);
        assertThat(prompt).contains("SERVER_OVERVIEW");
        assertThat(prompt).contains("CONNECT_TARGET");
        assertThat(prompt).contains("QUICK_CHECK");
        assertThat(prompt).contains("INVESTIGATE_SERVICE");
    }

    // ── classifyFromRaw ───────────────────────────────────────────────

    @Test
    void classifyFromRawShouldReturnScopeIntentPair() {
        String raw = "{\"scope\":\"REMOTE_SERVER\",\"intent\":\"SERVER_OVERVIEW\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var classifier = new IntentClassifier();
        String result = classifier.classifyFromRaw(raw, List.of(), null);
        assertThat(result).isEqualTo("REMOTE_SERVER/SERVER_OVERVIEW");
    }

    @Test
    void classifyFromRawShouldReturnClarifyOnFailure() {
        var classifier = new IntentClassifier();
        String result = classifier.classifyFromRaw("garbage", List.of(), null);
        assertThat(result).isEqualTo("CLARIFY");
    }

    @Test
    void classifyFromRawLocalProject() {
        String raw = "{\"scope\":\"LOCAL_PROJECT\",\"intent\":\"NONE\",\"targetId\":\"\",\"service\":\"\",\"reply\":\"\",\"reason\":\"\"}";
        var classifier = new IntentClassifier();
        String result = classifier.classifyFromRaw(raw, List.of(), null);
        assertThat(result).isEqualTo("LOCAL_PROJECT/NONE");
    }

    @Test
    void clearLocalCodeChangeRequestUsesDeterministicFallback() {
        var intent = IntentClassifier.deterministicLocalProjectFallback("看看本地代码有什么变动");

        assertThat(intent).isNotNull();
        assertThat(intent.scope()).isEqualTo(WorkScope.LOCAL_PROJECT);
        assertThat(intent.intent()).isEqualTo(ServerIntent.NONE);
    }

    @Test
    void remoteRequestDoesNotUseLocalProjectFallback() {
        assertThat(IntentClassifier.deterministicLocalProjectFallback(
            "看看服务器上的代码有什么变动")).isNull();
    }

    @Test
    void investigationRequestUsesTheActiveServerWithoutModelClassification() {
        var intent = IntentClassifier.deterministicInvestigationFallback(
            "调查 order-api 为什么持续返回 500", "test-server");

        assertThat(intent).isNotNull();
        assertThat(intent.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(intent.intent()).isEqualTo(ServerIntent.INVESTIGATE_SERVICE);
        assertThat(intent.targetId()).isEqualTo("test-server");
        assertThat(intent.service()).isEqualTo("order-api");
    }

    @Test
    void toolExplanationRequestUsesRemoteToolCatalogWhenConnected() {
        var intent = IntentClassifier.deterministicToolExplanationFallback(
            "10个工具都介绍介绍", "test-server");

        assertThat(intent).isNotNull();
        assertThat(intent.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(intent.intent()).isEqualTo(ServerIntent.TOOL_EXPLANATION);
        assertThat(intent.targetId()).isEqualTo("test-server");
    }

    @Test
    void toolExplanationWithoutConnectionExplainsMcpBoundaryInChat() {
        var intent = IntentClassifier.deterministicToolExplanationFallback(
            "各个工具都是干什么的，跟常规的 MCP 一样吗？", null);

        assertThat(intent).isNotNull();
        assertThat(intent.scope()).isEqualTo(WorkScope.CHAT);
        assertThat(intent.reply()).contains("MCP").contains("本地工具");
    }

    @Test
    void ambiguousQuestionDefaultsToActiveServerMode() {
        var intent = IntentClassifier.modeDefaultFallback(
            "这个问题怎么回事", "test-server", ClassifiedIntent.NONE);

        assertThat(intent.scope()).isEqualTo(WorkScope.REMOTE_SERVER);
        assertThat(intent.intent()).isEqualTo(ServerIntent.SERVER_QUERY);
        assertThat(intent.targetId()).isEqualTo("test-server");
    }

    @Test
    void ambiguousQuestionDefaultsToLocalModeWithoutConnection() {
        var intent = IntentClassifier.modeDefaultFallback(
            "继续看看", null, ClassifiedIntent.NONE);

        assertThat(intent.scope()).isEqualTo(WorkScope.LOCAL_PROJECT);
        assertThat(intent.intent()).isEqualTo(ServerIntent.NONE);
    }

    @Test
    void explicitRemoteQuestionWithoutConnectionStillRequiresClarification() {
        var clarify = ClassifiedIntent.clarify("请先指定服务器", "没有活动连接");

        assertThat(IntentClassifier.modeDefaultFallback(
            "看看远程服务器", null, clarify)).isSameAs(clarify);
    }
}
