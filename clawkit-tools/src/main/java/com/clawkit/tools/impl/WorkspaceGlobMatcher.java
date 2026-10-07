package com.clawkit.tools.impl;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.PatternSyntaxException;

/** NIO glob syntax with recursive directory segments also matching zero directories. */
final class WorkspaceGlobMatcher {
    private static final int MAX_VARIANTS = 256;

    private WorkspaceGlobMatcher() {}

    static PathMatcher compile(String pattern) {
        var fileSystem = FileSystems.getDefault();
        // Validate the original first; escaping, classes and alternatives remain NIO's responsibility.
        PathMatcher original = fileSystem.getPathMatcher("glob:" + pattern);
        List<Integer> optionalSegments = new ArrayList<>();
        boolean inClass = false;
        boolean segmentStart = true;
        boolean groupSegmentStart = false;
        boolean inGroup = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (inClass) {
                if (c == ']') inClass = false;
                continue;
            }
            if (c == '\\') { i++; segmentStart = false; continue; }
            if (c == '[') { inClass = true; segmentStart = false; continue; }
            if (c == '{') { inGroup = true; groupSegmentStart = segmentStart; continue; }
            if (c == ',' && inGroup) { segmentStart = groupSegmentStart; continue; }
            if (c == '}') { inGroup = false; segmentStart = false; continue; }
            if (pattern.startsWith("**/", i)
                && segmentStart) {
                optionalSegments.add(i);
                i += 2;
                segmentStart = true;
            } else segmentStart = c == '/';
        }
        if (optionalSegments.isEmpty()) return original;
        var variants = new LinkedHashSet<String>();
        variants.add(pattern);
        // Removing from right to left leaves every remaining original index unchanged.
        for (int n = optionalSegments.size() - 1; n >= 0; n--) {
            int index = optionalSegments.get(n);
            for (String variant : List.copyOf(variants)) {
                variants.add(variant.substring(0, index) + variant.substring(index + 3));
                if (variants.size() > MAX_VARIANTS) {
                    throw new PatternSyntaxException("recursive glob is too complex (maximum 256 variants)", pattern, index);
                }
            }
        }
        var matchers = variants.stream().map(value -> fileSystem.getPathMatcher("glob:" + value)).toList();
        return path -> matchers.stream().anyMatch(matcher -> matcher.matches(path));
    }
}
