package com.clawkit.cli.intent;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class IntentHandlerTest {

    @Test
    void quickCheckPromptRequiresDecisionFieldsAndRealEvidenceReference() {
        String prompt = IntentHandler.buildQuickCheckPrompt("test-server", "order-api");

        assertThat(prompt)
            .contains("test-server")
            .contains("order-api")
            .contains("目标")
            .contains("影响")
            .contains("结论")
            .contains("证据时间")
            .contains("下一步")
            .contains("observedAt")
            .contains("collectedAt")
            .contains("run://.../tool/...")
            .contains("证据不足")
            .contains("## 检查结果")
            .contains("只输出一次最终报告")
            .contains("全文不超过25行");
    }

    @Test
    void quickCheckPromptOmitsBlankServiceHint() {
        String prompt = IntentHandler.buildQuickCheckPrompt("test-server", " ");

        assertThat(prompt)
            .contains("快速检查服务器 test-server")
            .doesNotContain("重点关注:");
    }

    @Test
    void quickCheckStreamStartsAtSplitReportMarkerAndDropsPreamble() {
        var bytes = new ByteArrayOutputStream();
        var stream = new QuickCheckStreamRenderer(new PrintStream(bytes));

        stream.accept("我已经收集到证据，让我梳理。\n## 检");
        assertThat(stream.started()).isFalse();
        assertThat(bytes.toString()).isEmpty();

        stream.accept("查结果\n**状态**：异常");
        stream.accept("\n**下一步**：调查");
        stream.finish();

        assertThat(bytes.toString())
            .contains("## 检查结果")
            .contains("**状态**：异常")
            .contains("**下一步**：调查")
            .doesNotContain("让我梳理")
            .doesNotContain("我已经收集");
    }

    @Test
    void quickCheckFallbackCleansPreambleBeforeReport() {
        String cleaned = QuickCheckStreamRenderer.cleanFinalResult(
            "分析过程\n## 检查结果\n**状态**：正常");

        assertThat(cleaned)
            .startsWith("## 检查结果")
            .doesNotContain("分析过程");
    }

    @Test
    void serverQueryPromptPinsQuestionToActiveServerAndReadOnlyTools() {
        String prompt = IntentHandler.buildServerQueryPrompt(
            "test-server", "这个错误怎么回事");

        assertThat(prompt)
            .contains("服务器模式")
            .contains("test-server")
            .contains("远程只读工具")
            .contains("不要读取本地项目")
            .contains("这个错误怎么回事");
    }

    @Test
    void investigationCommandPreservesTheOriginalUserQuestion() {
        assertThat(IntentHandler.buildInvestigationCommand("test-server", "order-api",
            "调查 order-api 为什么 500"))
            .isEqualTo("investigate test-server order-api 调查 order-api 为什么 500");
    }
}
