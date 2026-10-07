package com.clawkit.tools.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.Test;

class WorkspaceGlobMatcherTest {
    @Test
    void preservesAlternativesAndCharacterClasses() {
        var matcher = WorkspaceGlobMatcher.compile("**/{report-[ab].json,health.json}");
        assertTrue(matcher.matches(Path.of("report-a.json")));
        assertTrue(matcher.matches(Path.of("nested/report-b.json")));
        assertTrue(matcher.matches(Path.of("health.json")));
        assertFalse(matcher.matches(Path.of("report-c.json")));
        assertFalse(matcher.matches(Path.of("notes.json")));
    }

    @Test
    void ordinaryStarStaysInOneDirectory() {
        var matcher = WorkspaceGlobMatcher.compile("src/*.java");
        assertTrue(matcher.matches(Path.of("src/App.java")));
        assertFalse(matcher.matches(Path.of("src/deep/App.java")));
        assertFalse(matcher.matches(Path.of("App.java")));
    }

    @Test
    void boundsIndependentRecursiveSegments() {
        String pattern = "**/a/**/b/**/c/**/d/**/e/**/f/**/g/**/h/**/i.json";
        assertThrows(PatternSyntaxException.class, () -> WorkspaceGlobMatcher.compile(pattern));
    }

    @Test
    void literalCommaAndEscapedBraceDoNotCreateDirectoryBoundaries() {
        assertFalse(WorkspaceGlobMatcher.compile("name,**/health.json").matches(Path.of("name,health.json")));
        assertFalse(WorkspaceGlobMatcher.compile("report\\{**/health.json").matches(Path.of("report{health.json")));
        assertTrue(WorkspaceGlobMatcher.compile("{**/health.json,other.txt}").matches(Path.of("health.json")));
    }
}
