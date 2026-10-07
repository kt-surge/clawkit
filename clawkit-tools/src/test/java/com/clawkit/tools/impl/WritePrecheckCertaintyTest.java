package com.clawkit.tools.impl;

import com.clawkit.tools.Result;
import com.clawkit.tools.ToolExecutionRequest;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.action.FailureClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class WritePrecheckCertaintyTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolExecutionRequest request(boolean overwrite) {
        var args = JSON.createObjectNode().put("path", "state.json").put("content", "new");
        if (overwrite) args.put("overwrite", true);
        return new ToolExecutionRequest("write-state", "write", args, Instant.now());
    }

    @Test void overwriteRefusalIsKnownToHaveNoEffect() throws Exception {
        Files.writeString(workspace.resolve("state.json"), "old");
        var result = new WriteTool(workspace).execute(request(false));
        assertThat(result.status()).isEqualTo(ToolExecutionStatus.TOOL_ERROR);
        assertThat(result.errorCode()).isEqualTo("OVERWRITE_REQUIRED");
        assertThat(result.effectCertainty()).isEqualTo(EffectCertainty.NO_EFFECT_CONFIRMED);
        assertThat(result.failureClass()).isEqualTo(FailureClass.LOCAL_ERROR_NO_EFFECT);
        assertThat(Files.readString(workspace.resolve("state.json"))).isEqualTo("old");
        assertThat(Files.exists(workspace.resolve(".state.json.tmp"))).isFalse();
    }

    @Test void explicitOverwriteStillUsesTheOrdinaryWriter() throws Exception {
        Files.writeString(workspace.resolve("state.json"), "old");
        var result = new WriteTool(workspace).execute(request(true));
        assertThat(result.success()).isTrue();
        assertThat(Files.readString(workspace.resolve("state.json"))).isEqualTo("new");
    }

    @Test void errorCodeAloneCannotClaimThatExecutionHadNoEffect() throws Exception {
        var tool = new WriteTool(workspace) {
            @Override public Result<String> execute(String arguments) {
                try { Files.writeString(workspace.resolve("state.json"), "partially changed"); }
                catch (Exception e) { throw new IllegalStateException(e); }
                return new Result.Err<>(new Result.ErrorInfo("OVERWRITE_REQUIRED", "arbitrary execution error"));
            }
        };
        var result = tool.execute(request(true));
        assertThat(result.effectCertainty()).isEqualTo(EffectCertainty.EFFECT_UNKNOWN);
        assertThat(result.failureClass()).isEqualTo(FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN);
        assertThat(Files.readString(workspace.resolve("state.json"))).isEqualTo("partially changed");
    }
}
