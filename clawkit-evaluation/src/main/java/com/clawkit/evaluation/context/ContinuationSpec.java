package com.clawkit.evaluation.context;

import com.clawkit.tools.schema.Role;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;

/** Explicit development and frozen holdout contracts. Gold never becomes agent input. */
public record ContinuationSpec(Split split, String version, long seed, int repetitions, Limits limits, Model model,
                               Settings settings, List<Task> tasks) {
    public enum Split { DEVELOPMENT_ONLY, FROZEN_HOLDOUT }
    public enum Suite { COMPRESSION, MEMORY }
    public enum Arm { C0_RECENT_WHOLE_TURNS, C1_ROLLING_SUMMARY, C2_CURRENT_LADDER_GENERAL,
        M0_NO_HISTORY, M1_FULL_ARCHIVE_READ_GREP, M2_CURRENT_MEMORY_AND_SESSION_BM25,
        C2_FROZEN_ORIGINAL_CONTEXT, C3_CANDIDATE_CONTEXT, M2_NATIVE_MEMORY_SESSION;
        public boolean nativeMemory() { return this == M2_CURRENT_MEMORY_AND_SESSION_BM25 || this == M2_NATIVE_MEMORY_SESSION; }
    }
    public record Limits(int instanceProviderCalls, long instanceTotalTokens, int totalProviderCalls,
                         long totalTokens, int outputTokensPerCall, int maxRetries, int roundDeadlineSeconds) {}
    public record Model(String endpoint, String model) {}
    public record Settings(int contextWindow, int recentWholeTurns, int framingReserveTokens,
                           int instanceDeadlineSeconds, int instanceToolCalls, String encoding) {}
    public record AgentInput(List<ReplayCase.HistorySource> histories, String query, Map<String, String> workspaceFiles) {
        public AgentInput { histories = List.copyOf(histories); workspaceFiles = Map.copyOf(workspaceFiles); }
    }
    public record Task(String id, String family, Suite suite, AgentInput agentInput, JsonNode gold) {
        public ContinuationTaskScorer.Gold outcomeGold() {
            var fields = new LinkedHashMap<String, JsonNode>();
            gold.path("requiredFields").fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
            var additional = new LinkedHashMap<String, Map<String, JsonNode>>();
            gold.path("additionalJsonFiles").fields().forEachRemaining(file -> {
                var expected = new LinkedHashMap<String, JsonNode>();
                file.getValue().fields().forEachRemaining(field -> expected.put(field.getKey(), field.getValue()));
                additional.put(file.getKey(), expected);
            });
            return new ContinuationTaskScorer.Gold(gold.path("jsonFile").asText(), fields,
                strings(gold.path("unchangedFiles")), strings(gold.path("forbiddenWriteTargets")),
                strings(gold.path("requiredReadTargets")), gold.path("forbiddenExtraFields").asBoolean(false), additional);
        }
    }
    public record Instance(String id, String taskId, Suite suite, Arm arm, int repetition) {}

    public static ContinuationSpec parse(JsonNode json) throws Exception { return parse(json, Split.DEVELOPMENT_ONLY); }
    public static ContinuationSpec parseFrozen(JsonNode json) throws Exception { return parse(json, Split.FROZEN_HOLDOUT); }
    static ContinuationSpec parseArchived(JsonNode json) throws Exception {
        return parse(json, Split.valueOf(json.path("split").asText()));
    }
    private static ContinuationSpec parse(JsonNode json, Split expected) throws Exception {
        if (!json.path("split").asText().equals(expected.name()) || !json.path("mode").asText().equals("live-continuation")) {
            throw new IllegalArgumentException("explicit matching continuation split required");
        }
        var tasks = new ArrayList<Task>();
        for (var task : json.path("tasks")) tasks.add(new Task(task.path("id").asText(), task.path("family").asText("development"),
            Suite.valueOf(task.path("suite").asText()), EvaluationArtifacts.JSON.treeToValue(task.path("agentInput"), AgentInput.class),
            task.path("gold").deepCopy()));
        var spec = new ContinuationSpec(expected, json.path("version").asText(), json.path("seed").asLong(),
            json.path("repetitions").asInt(), EvaluationArtifacts.JSON.treeToValue(json.path("limitsDraft"), Limits.class),
            EvaluationArtifacts.JSON.treeToValue(json.path("modelDraft"), Model.class),
            EvaluationArtifacts.JSON.treeToValue(json.path("settingsDraft"), Settings.class), List.copyOf(tasks));
        spec.validate();
        if (json.path("plannedTasks").asInt() != tasks.size() || json.path("plannedInstances").asInt() != spec.instances().size()) {
            throw new IllegalArgumentException("declared continuation denominator differs");
        }
        for (var suite : Suite.values()) {
            var declared = strings(json.path("suites").path(suite.name()));
            if (!declared.equals(arms(suite, expected).stream().map(Enum::name).toList())) throw new IllegalArgumentException("suite arm contract differs");
        }
        return spec;
    }

    private void validate() {
        if (limits == null || model == null || settings == null || model.endpoint() == null || model.model() == null
            || settings.encoding() == null) throw new IllegalArgumentException("explicit model, budget and evaluation settings required");
        boolean frozen = split == Split.FROZEN_HOLDOUT;
        int expectedTasks = frozen ? 24 : 6;
        int expectedRepetitions = frozen ? 3 : 1;
        int expectedInstances = expectedTasks * expectedRepetitions * 3;
        if (version == null || version.isBlank() || repetitions != expectedRepetitions || tasks.size() != expectedTasks)
            throw new IllegalArgumentException("fixed task and repetition contract differs");
        if (!model.endpoint().equals("https://api.deepseek.com") || !model.model().equals("deepseek-v4-flash")) throw new IllegalArgumentException("model contract must be reviewed before changing");
        if (limits.instanceProviderCalls() < 1 || limits.instanceProviderCalls() > (frozen ? 12 : 8) || limits.instanceTotalTokens() < 1
            || limits.instanceTotalTokens() > (frozen ? 45_000 : 30_000) || limits.totalProviderCalls() != limits.instanceProviderCalls() * expectedInstances
            || limits.totalTokens() != limits.instanceTotalTokens() * expectedInstances || limits.outputTokensPerCall() != 1024 || limits.maxRetries() != 0) {
            throw new IllegalArgumentException("continuation budget contract differs");
        }
        if (settings.contextWindow() < 2048 || settings.contextWindow() > 8192 || settings.recentWholeTurns() != 3
            || settings.framingReserveTokens() < 1024 || settings.instanceDeadlineSeconds() < 1
            || settings.instanceDeadlineSeconds() > 180 || settings.instanceToolCalls() != (frozen ? 24 : 16) || !settings.encoding().equals("cl100k_base")) {
            throw new IllegalArgumentException("evaluation settings must be explicit and bounded");
        }
        if (frozen && (settings.contextWindow() != 4096 || limits.roundDeadlineSeconds() != 7200))
            throw new IllegalArgumentException("frozen window and two-hour round deadline required");
        var ids = new HashSet<String>();
        for (var task : tasks) {
            identifier(task.id());
            identifier(task.family());
            if (frozen && task.agentInput().histories().size() > 3) throw new IllegalArgumentException("at most three original histories per frozen task");
            if (!ids.add(task.id()) || task.agentInput().query() == null || task.agentInput().query().isBlank()) throw new IllegalArgumentException("unique tasks with query required");
            var sources = new HashSet<String>();
            for (var source : task.agentInput().histories()) {
                identifier(source.id());
                if (!sources.add(source.id()) || source.revision() < 1 || source.observedAt() == null || source.messages().isEmpty()
                    || source.messages().stream().anyMatch(m -> m.role() == Role.SYSTEM)
                    || source.messages().getFirst().role() != Role.USER
                    || !ContinuationBaselinePipeline.validToolProtocol(source.messages())) throw new IllegalArgumentException("invalid raw history source");
            }
            if (task.suite() == Suite.COMPRESSION && task.agentInput().histories().size() != 1) throw new IllegalArgumentException("compression starts from one saved conversation");
            for (var path : task.agentInput().workspaceFiles().keySet()) {
                relativeFile(path);
                if (path.startsWith("archive/") || path.startsWith(".clawkit/")) throw new IllegalArgumentException("reserved evaluator workspace path");
            }
            var gold = task.outcomeGold();
            relativeFile(gold.jsonFile());
            if (task.agentInput().workspaceFiles().containsKey(gold.jsonFile()) || gold.requiredFields().isEmpty()) throw new IllegalArgumentException("output must be created by task");
            gold.unchangedFiles().forEach(ContinuationSpec::relativeFile);
            gold.forbiddenWriteTargets().forEach(ContinuationSpec::relativeFile);
            gold.requiredReadTargets().forEach(ContinuationSpec::relativeFile);
            for (var extra : gold.additionalJsonFiles().entrySet()) {
                relativeFile(extra.getKey());
                if (extra.getValue().isEmpty() || extra.getKey().equals(gold.jsonFile())) throw new IllegalArgumentException("nonempty distinct additional output required");
            }
            if (gold.allOutputFiles().stream().anyMatch(file -> gold.unchangedFiles().contains(file) || gold.forbiddenWriteTargets().contains(file)))
                throw new IllegalArgumentException("output target is protected or forbidden");
            if (!task.agentInput().workspaceFiles().keySet().containsAll(gold.unchangedFiles())
                || !task.agentInput().workspaceFiles().keySet().containsAll(gold.requiredReadTargets())) throw new IllegalArgumentException("protected/read source absent");
            var workspaceSources = task.gold().path("workspaceEvidenceSources").fields();
            while (workspaceSources.hasNext()) {
                var source = workspaceSources.next(); identifier(source.getKey()); relativeFile(source.getValue().asText());
                if (!sources.add(source.getKey()) || !task.agentInput().workspaceFiles().containsKey(source.getValue().asText())
                    || !gold.requiredReadTargets().contains(source.getValue().asText()))
                    throw new IllegalArgumentException("fresh source requires a registered file and mandatory read");
            }
            for (var alternative : task.gold().path("evidenceAlternatives")) {
                if (strings(alternative).isEmpty() || !sources.containsAll(strings(alternative))) throw new IllegalArgumentException("evidence source absent");
            }
            if (task.suite() == Suite.MEMORY && !task.gold().path("unanswerable").asBoolean(false)) {
                var factSources = task.gold().path("factSources");
                var fields = new HashSet<String>(); factSources.fieldNames().forEachRemaining(fields::add);
                if (!factSources.isObject() || !fields.equals(gold.requiredFields().keySet())) throw new IllegalArgumentException("per-field source rubric required");
                for (var alternatives : factSources) {
                    if (!alternatives.isArray() || alternatives.isEmpty()) throw new IllegalArgumentException("fact source alternatives required");
                    for (var alternative : alternatives) if (!alternative.isArray() || strings(alternative).isEmpty()
                        || !sources.containsAll(strings(alternative))) throw new IllegalArgumentException("fact source absent");
                }
            }
        }
        for (var suite : Suite.values()) {
            var group = tasks.stream().filter(t -> t.suite() == suite).toList();
            if (group.size() != (frozen ? 12 : 3)) throw new IllegalArgumentException("fixed tasks per suite required");
            if (frozen) {
                var families = group.stream().collect(java.util.stream.Collectors.groupingBy(Task::family, java.util.stream.Collectors.counting()));
                if (families.size() != 4 || families.values().stream().anyMatch(count -> count != 3))
                    throw new IllegalArgumentException("four frozen families with three tasks each required");
            }
        }
    }

    public List<Instance> instances() {
        var result = new ArrayList<Instance>();
        var random = new Random(seed);
        for (int repetition = 1; repetition <= repetitions; repetition++) {
            for (var suite : Suite.values()) {
                var group = new ArrayList<>(tasks.stream().filter(t -> t.suite() == suite).toList());
                Collections.shuffle(group, random);
                for (int i = 0; i < group.size(); i++) {
                    var task = group.get(i);
                    var arms = arms(suite, split);
                    for (int j = 0; j < arms.size(); j++) {
                        var arm = arms.get((i + j + repetition - 1) % arms.size());
                        result.add(new Instance(task.id() + "-" + arm.name().toLowerCase(Locale.ROOT) + "-r" + repetition, task.id(), suite, arm, repetition));
                    }
                }
            }
        }
        return List.copyOf(result);
    }
    public Task task(Instance instance) { return tasks.stream().filter(t -> t.id().equals(instance.taskId())).findFirst().orElseThrow(); }
    public static List<Arm> arms(Suite suite) { return suite == Suite.COMPRESSION
        ? List.of(Arm.C0_RECENT_WHOLE_TURNS, Arm.C1_ROLLING_SUMMARY, Arm.C2_CURRENT_LADDER_GENERAL)
        : List.of(Arm.M0_NO_HISTORY, Arm.M1_FULL_ARCHIVE_READ_GREP, Arm.M2_CURRENT_MEMORY_AND_SESSION_BM25); }
    public static List<Arm> arms(Suite suite, Split split) {
        if (split == Split.DEVELOPMENT_ONLY) return arms(suite);
        return suite == Suite.COMPRESSION ? List.of(Arm.C1_ROLLING_SUMMARY, Arm.C2_FROZEN_ORIGINAL_CONTEXT, Arm.C3_CANDIDATE_CONTEXT)
            : List.of(Arm.M0_NO_HISTORY, Arm.M1_FULL_ARCHIVE_READ_GREP, Arm.M2_NATIVE_MEMORY_SESSION);
    }
    static List<String> strings(JsonNode array) { var values = new ArrayList<String>(); array.forEach(value -> values.add(value.asText())); return List.copyOf(values); }
    static void identifier(String id) { if (id == null || !id.matches("[a-z][a-z0-9-]{0,80}")) throw new IllegalArgumentException("safe identifier required"); }
    static void relativeFile(String file) {
        if (file == null || file.isBlank() || file.contains("\\") || file.contains(":") || Path.of(file).isAbsolute()
            || Arrays.asList(file.split("/", -1)).stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw new IllegalArgumentException("normalized relative file required");
        }
    }
}
