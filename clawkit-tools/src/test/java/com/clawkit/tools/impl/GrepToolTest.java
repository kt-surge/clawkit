package com.clawkit.tools.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.clawkit.tools.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GrepToolTest {

    private static final String TEST_JAVA = """
        package test;
        public class App {
            @Override
            public String toString() {
                return "App";
            }
        }
        """;

    @Test
    void findsRegexInFiles() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-ut");
        Files.writeString(workDir.resolve("App.java"), TEST_JAVA, StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"@Override\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("@Override"));
        assertTrue(output.contains("App.java:3"));
    }

    @Test
    void searchWithGlobFilter() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-ut2");
        Files.createDirectories(workDir.resolve("src"));
        Files.writeString(workDir.resolve("src/App.java"), "@Override", StandardCharsets.UTF_8);
        Files.writeString(workDir.resolve("src/README.md"), "@Override in markdown", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"@Override\",\"glob\":\"**/*.java\"}");

        assertInstanceOf(Result.Ok.class, r);
        String output = ((Result.Ok<String>) r).data();
        assertTrue(output.contains("App.java"));
        assertFalse(output.contains("README.md"));
    }

    @Test
    void noMatchesReturnsOk() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-ut3");
        Files.writeString(workDir.resolve("x.txt"), "hello world", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"NONEXISTENT\"}");

        assertInstanceOf(Result.Ok.class, r);
        assertTrue(((Result.Ok<String>) r).data().contains("未找到"));
    }

    @Test
    void invalidRegexReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-ut4");
        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"[unclosed\"}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
        assertTrue(((Result.Err<String>) r).error().message().contains("语法"));
    }

    @Test
    void skipsIgnoredDirectories() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-ut5");
        Files.createDirectories(workDir.resolve(".git"));
        Files.createDirectories(workDir.resolve("target"));
        Files.writeString(workDir.resolve(".git/config"), "TOKEN=secret", StandardCharsets.UTF_8);
        Files.writeString(workDir.resolve("target/build.log"), "TOKEN=secret", StandardCharsets.UTF_8);
        Files.createDirectories(workDir.resolve("src"));
        Files.writeString(workDir.resolve("src/App.java"), "no secret here", StandardCharsets.UTF_8);

        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{\"pattern\":\"TOKEN\"}");

        assertInstanceOf(Result.Ok.class, r);
        assertTrue(((Result.Ok<String>) r).data().contains("未找到"));
    }

    @Test
    void missingPatternReturnsError() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-ut6");
        GrepTool tool = new GrepTool(workDir);
        Result<String> r = tool.execute("{}");

        assertInstanceOf(Result.Err.class, r);
        assertEquals("T-002", ((Result.Err<String>) r).error().errorCode());
    }

    @Test
    void recursiveGlobFilterIncludesRootFiles() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-root");
        Files.writeString(workDir.resolve("current-health.json"), "healthy=true");
        Files.createDirectories(workDir.resolve("snapshots"));
        Files.writeString(workDir.resolve("snapshots/current-health.json"), "healthy=true");
        Files.writeString(workDir.resolve("notes.txt"), "healthy=true");
        Result<String> result = new GrepTool(workDir).execute("{\"pattern\":\"healthy\",\"glob\":\"**/*.json\"}");
        assertInstanceOf(Result.Ok.class, result);
        String output = ((Result.Ok<String>) result).data();
        assertTrue(output.contains("current-health.json:1:"));
        assertTrue(output.contains("snapshots/current-health.json:1:"));
        assertEquals(2, output.lines().filter(line -> line.contains("healthy=true")).count());
        assertFalse(output.contains("notes.txt"));
    }

    @Test
    void invalidGlobFilterReturnsToolError() throws IOException {
        Path workDir = Files.createTempDirectory("clawkit-grep-invalid");
        Result<String> result = new GrepTool(workDir).execute("{\"pattern\":\"healthy\",\"glob\":\"[unclosed\"}");
        assertInstanceOf(Result.Err.class, result);
        assertEquals("T-002", ((Result.Err<String>) result).error().errorCode());
    }
}
