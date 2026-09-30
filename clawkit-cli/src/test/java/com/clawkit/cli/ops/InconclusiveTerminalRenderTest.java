package com.clawkit.cli.ops;

import com.clawkit.ops.delivery.InvestigationView;
import com.clawkit.ops.delivery.UserIncidentStatus;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InconclusiveTerminalRenderTest {

    @Test
    void inconclusiveViewShowsAllEightSafeFactsAndAConstrainedEvidenceGapHint() {
        var view = new InvestigationView("inc-fixture-inconclusive", "fixture", "order-api",
            UserIncidentStatus.INCONCLUSIVE, "调查完成",
            List.of("服务状态 running", "容器状态 running", "端口可达", "HTTP 200",
                "网关最近窗口无日志", "order-api 最近窗口无日志", "健康状态 unhealthy", "指标仍可访问"),
            "证据不足，无法确定原因",
            "当前证据不足，未触发修复。优先补充：服务健康检查失败原因、应用请求日志、服务资源使用情况。",
            false, "", "", "建议人工登录服务器检查。详情: /ops inspect inc-fixture-inconclusive",
            Instant.parse("2026-09-20T12:00:00Z"), Instant.parse("2026-09-20T12:00:00Z"),
            "incidents/inc-fixture-inconclusive/");
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            new JLineInvestigationInteraction(null).onFinalResult(view);
        } finally {
            System.setOut(original);
        }

        String text = output.toString(StandardCharsets.UTF_8);
        assertThat(text)
            .contains("order-api 最近窗口无日志")
            .contains("指标仍可访问")
            .contains("当前证据不足，未触发修复")
            .doesNotContain("approve");
    }
}
