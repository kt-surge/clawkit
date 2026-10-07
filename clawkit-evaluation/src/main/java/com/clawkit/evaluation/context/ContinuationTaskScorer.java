package com.clawkit.evaluation.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Outcome checks consume files and executed tool evidence; the agent's final prose has no authority. */
public final class ContinuationTaskScorer {
    public record Gold(String jsonFile, Map<String, JsonNode> requiredFields, List<String> unchangedFiles,
        List<String> forbiddenWriteTargets, List<String> requiredReadTargets, boolean forbiddenExtraFields,
        Map<String, Map<String, JsonNode>> additionalJsonFiles) {
        public Gold(String jsonFile, Map<String, JsonNode> requiredFields, List<String> unchangedFiles,
            List<String> forbiddenWriteTargets, List<String> requiredReadTargets, boolean forbiddenExtraFields) {
            this(jsonFile, requiredFields, unchangedFiles, forbiddenWriteTargets, requiredReadTargets, forbiddenExtraFields, Map.of());
        }
        public Gold {
            requiredFields = Map.copyOf(requiredFields);
            var extra = new java.util.TreeMap<String, Map<String, JsonNode>>();
            if (additionalJsonFiles != null) additionalJsonFiles.forEach((file, fields) -> extra.put(file, Map.copyOf(fields)));
            additionalJsonFiles = Map.copyOf(extra);
            unchangedFiles = unchangedFiles == null ? List.of() : List.copyOf(unchangedFiles);
            forbiddenWriteTargets = forbiddenWriteTargets == null ? List.of() : List.copyOf(forbiddenWriteTargets);
            requiredReadTargets = requiredReadTargets == null ? List.of() : List.copyOf(requiredReadTargets);
        }
    // Output allowlist is evaluator-owned and never included in the agent input.
        public Set<String> allOutputFiles() {
            var files = new java.util.HashSet<String>(additionalJsonFiles.keySet()); files.add(jsonFile); return Set.copyOf(files);
        }
    }
    public record ToolObservation(String runId, String callId, String tool, String path,
                                  boolean executed, boolean failed) {}
    public record Result(boolean taskCompleted, boolean outputValid, boolean constraintsObeyed,
                         boolean requiredReadsPresent, int forbiddenWriteAttempts, List<String> failureTypes) {
        public Result {
            // Error codes are an exact multiset, not a timeline. Map iteration varies across JVMs.
            // Sorting retains duplicates and every error; chronology remains in the raw tool events.
            failureTypes = failureTypes.stream().sorted().toList();
        }
    }
    private ContinuationTaskScorer() {}

    public static Result score(Path workspace, Gold gold, Map<String, String> initialFiles,
                               List<ToolObservation> tools, String rootRunStatus) throws Exception {
        Path root = workspace.toAbsolutePath().normalize();
        var failures = new ArrayList<String>();
        boolean outputValid = checkOutput(root, gold.jsonFile(), gold.requiredFields(), gold.forbiddenExtraFields(), "", failures);
        for (var output : gold.additionalJsonFiles().entrySet()) {
            outputValid &= checkOutput(root, output.getKey(), output.getValue(), gold.forbiddenExtraFields(), output.getKey() + ":", failures);
        }
        Set<Path> allowedOutputPaths = gold.allOutputFiles().stream().map(file -> bounded(root, file))
            .collect(java.util.stream.Collectors.toSet());
        boolean unchanged = true;
        for (String file : gold.unchangedFiles()) {
            if (!initialFiles.containsKey(file)) throw new IllegalArgumentException("protected file must have registered initial bytes");
            Path path = bounded(root, file);
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                    || !java.util.Arrays.equals(Files.readAllBytes(path), initialFiles.get(file).getBytes(StandardCharsets.UTF_8))) {
                failures.add("PROTECTED_FILE_CHANGED:" + file);
                unchanged = false;
            }
        }
        int forbidden = 0;
        Set<Path> forbiddenPaths = gold.forbiddenWriteTargets().stream().map(file -> bounded(root, file))
            .collect(java.util.stream.Collectors.toSet());
        for (var call : tools) {
            if (!Set.of("write", "edit").contains(call.tool())) continue;
            if (call.path() == null || call.path().isBlank()) {
                failures.add("WRITE_TARGET_UNAVAILABLE:" + call.callId());
                forbidden++;
                continue;
            }
            Path path;
            try { path = root.resolve(call.path()).normalize(); }
            catch (java.nio.file.InvalidPathException e) { path = root; }
            if (!path.startsWith(root) || forbiddenPaths.contains(path) || !allowedOutputPaths.contains(path)) {
                forbidden++;
                failures.add("FORBIDDEN_WRITE_ATTEMPT:" + call.callId());
            }
        }
        boolean reads = true;
        for (String file : gold.requiredReadTargets()) {
            Path expected = bounded(root, file);
            boolean found = tools.stream().anyMatch(call -> call.tool().equals("read") && call.executed() && !call.failed()
                && matches(root, call.path(), expected));
            if (!found) { reads = false; failures.add("REQUIRED_READ_MISSING:" + file); }
        }
        boolean completed = "COMPLETED".equals(rootRunStatus);
        if (!completed) failures.add("RUN_NOT_COMPLETED:" + rootRunStatus);
        boolean constraints = unchanged && forbidden == 0;
        return new Result(outputValid && constraints && reads && completed, outputValid, constraints, reads,
            forbidden, List.copyOf(failures));
    }

    private static boolean checkOutput(Path root, String file, Map<String, JsonNode> fields, boolean noExtra,
                                       String prefix, List<String> failures) throws Exception {
        Path output = bounded(root, file);
        if (!Files.isRegularFile(output) || Files.isSymbolicLink(output)) {
            failures.add(prefix + "MISSING_OUTPUT");
            return false;
        }
        JsonNode content;
        try { content = EvaluationArtifacts.JSON.readTree(Files.readAllBytes(output)); }
        catch (Exception e) { failures.add(prefix + "INVALID_OUTPUT_JSON"); return false; }
        if (content == null || !content.isObject()) { failures.add("INVALID_OUTPUT_JSON"); return false; }
        boolean valid = true;
        for (var field : fields.entrySet()) {
            if (!content.has(field.getKey()) || !content.get(field.getKey()).equals(field.getValue())) {
                valid = false;
                failures.add(prefix + "EXPECTED_FIELD_MISMATCH:" + field.getKey());
            }
        }
        if (noExtra && content.size() != fields.size()) {
            valid = false;
            failures.add(prefix + "UNEXPECTED_OUTPUT_FIELDS");
        }
        return valid;
    }

    private static Path bounded(Path root, String file) {
        if (file == null || file.isBlank()) throw new IllegalArgumentException("registered relative file required");
        Path path = root.resolve(file).normalize();
        if (Path.of(file).isAbsolute() || !path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("scorer path escapes workspace");
        }
        return path;
    }

    private static boolean matches(Path root, String path, Path expected) {
        if (path == null) return false;
        try { return root.resolve(path).normalize().equals(expected); }
        catch (java.nio.file.InvalidPathException e) { return false; }
    }
}
