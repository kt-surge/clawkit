package com.clawkit.tools.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.clawkit.tools.Result;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GlobToolTest {

    @Test
    void findsJavaFiles() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-ut");
        Files.createDirectories(workDir.resolve("src/main/java/com/test"));
        Files.createFile(workDir.resolve("src/main/java/com/test/App.java"));
        Files.createFile(workDir.resolve("src/main/java/com/test/Util.java"));
        Files.createFile(workDir.resolve("README.md"));

        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"**/*.java\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("App.java"));
        assertTrue(output.contains("Util.java"));
        assertFalse(output.contains("README.md"));
    }

    @Test
    void findsSpecificDirectory() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-ut2");
        Files.createDirectories(workDir.resolve("src/main/java"));
        Files.createDirectories(workDir.resolve("src/test/java"));
        Files.createFile(workDir.resolve("src/main/java/App.java"));
        Files.createFile(workDir.resolve("src/test/java/AppTest.java"));

        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"src/test/**/*.java\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("AppTest.java"));
        assertFalse(output.contains("App.java"));
    }

    @Test
    void noMatchesReturnsOk() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-ut3");
        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"**/*.rs\"}");

        assertInstanceOf(Result.Ok.class, r);
        assertTrue(((Result.Ok<String>) r).data().contains("未找到"));
    }

    @Test
    void missingPatternReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-ut4");
        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
    }

    @Test
    void emptyPatternReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-ut5");
        GlobTool tool = new GlobTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"\"}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
    }

    @Test
    void recursivePatternsIncludeRootAndNestedFiles() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-root");
        Files.writeString(workDir.resolve("current-health.json"), "{\"healthy\":true}");
        Files.createDirectories(workDir.resolve("snapshots"));
        Files.writeString(workDir.resolve("snapshots/current-health.json"), "{}");
        Files.writeString(workDir.resolve("schema.sql"), "-- accepted");
        GlobTool tool = new GlobTool(workDir);
        for (String pattern : new String[] {"**/current-health.json", "**/*.json", "**/*"}) {
            Result<String> result = tool.execute("{\"pattern\":\"" + pattern + "\"}");
            assertInstanceOf(Result.Ok.class, result);
            String output = ((Result.Ok<String>) result).data();
            assertTrue(output.contains("  current-health.json\n"), pattern + " must include the root file");
            assertTrue(output.contains("  snapshots/current-health.json\n"), pattern + " must include nested files");
        }
    }

    @Test
    void recursiveSegmentCanMatchZeroDirectoriesInsidePattern() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-segment");
        Files.createDirectories(workDir.resolve("src/deep"));
        Files.createDirectories(workDir.resolve("other"));
        Files.createFile(workDir.resolve("src/Config.java"));
        Files.createFile(workDir.resolve("src/deep/Config.java"));
        Files.createFile(workDir.resolve("other/Config.java"));
        Result<String> result = new GlobTool(workDir).execute("{\"pattern\":\"src/**/Config.java\"}");
        assertInstanceOf(Result.Ok.class, result);
        String output = ((Result.Ok<String>) result).data();
        assertTrue(output.contains("  src/Config.java\n"));
        assertTrue(output.contains("  src/deep/Config.java\n"));
        assertFalse(output.contains("other/Config.java"));
    }

    @Test
    void invalidGlobReturnsToolError() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-glob-invalid");
        Result<String> result = new GlobTool(workDir).execute("{\"pattern\":\"[unclosed\"}");
        assertInstanceOf(Result.Err.class, result);
        assertEquals("T-002", ((Result.Err<String>) result).error().errorCode());
    }
}
