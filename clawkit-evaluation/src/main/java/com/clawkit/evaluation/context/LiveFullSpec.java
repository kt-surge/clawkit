package com.clawkit.evaluation.context;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Independent empty-history tasks. Environment data is separate from both AgentInput and outcome gold. */
record LiveFullSpec(String version, ContinuationSpec.Limits limits, ContinuationSpec.Model model,
                    ContinuationSpec.Settings settings, List<Task> tasks) {
    record AgentInput(String query, Map<String,String> workspaceFiles) {
        AgentInput { workspaceFiles = Map.copyOf(workspaceFiles); }
    }
    record EnvironmentProgram(String kind, String statusFile, List<JsonNode> revisions, List<String> previewFiles) {
        EnvironmentProgram { revisions = List.copyOf(revisions); previewFiles = List.copyOf(previewFiles); }
    }
    record Task(String id, AgentInput agentInput, EnvironmentProgram environment, ContinuationTaskScorer.Gold gold) {}
    record Instance(String id, String taskId, ContinuationSpec.Arm arm) {}
    static LiveFullSpec parse(JsonNode input) throws Exception {
        if (!input.path("split").asText().equals("INDEPENDENT_LIVE_FULL") || !input.path("mode").asText().equals("live-full")
                || input.path("plannedTasks").asInt()!=2 || input.path("plannedInstances").asInt()!=6 || input.path("repetitions").asInt()!=1)
            throw new IllegalArgumentException("independent two-task / six-instance full contract required");
        if (input.path("version").asText().equals("context-memory-live-full-acceptance-v2")
                && !input.path("completionAcceptance").asText().equals(LiveFullPublicAcceptance.ID))
            throw new IllegalArgumentException("v2 public completion acceptance declaration required");
        if (input.path("version").asText().equals("context-memory-live-full-v1") && input.has("completionAcceptance"))
            throw new IllegalArgumentException("v1 must not silently enable v2 acceptance");
        var tasks = new ArrayList<Task>();
        for (var task : input.path("tasks")) {
            var agent = task.path("agentInput");
            if (!agent.isObject() || agent.size()!=2 || !agent.has("query") || !agent.has("workspaceFiles") || agent.has("histories"))
                throw new IllegalArgumentException("only query and workspaceFiles may enter the empty-history agent");
            var goldNode = task.path("gold");
            var parsedGold = new ContinuationSpec.Task(task.path("id").asText(), "live-full", ContinuationSpec.Suite.COMPRESSION,
                new ContinuationSpec.AgentInput(List.of(), agent.path("query").asText(), Map.of()), goldNode).outcomeGold();
            tasks.add(new Task(task.path("id").asText(), EvaluationArtifacts.JSON.treeToValue(agent, AgentInput.class),
                EvaluationArtifacts.JSON.treeToValue(task.path("environmentProgram"), EnvironmentProgram.class), parsedGold));
        }
        var result = new LiveFullSpec(input.path("version").asText(), EvaluationArtifacts.JSON.treeToValue(input.path("limitsDraft"), ContinuationSpec.Limits.class),
            EvaluationArtifacts.JSON.treeToValue(input.path("modelDraft"), ContinuationSpec.Model.class),
            EvaluationArtifacts.JSON.treeToValue(input.path("settingsDraft"), ContinuationSpec.Settings.class), List.copyOf(tasks));
        result.validate(); return result;
    }
    private void validate() {
        if (!Set.of("context-memory-live-full-v1","context-memory-live-full-acceptance-v2").contains(version) || tasks.size()!=2 || limits==null || model==null || settings==null
            || !model.equals(new ContinuationSpec.Model("https://api.deepseek.com","deepseek-v4-flash"))
            || !limits.equals(new ContinuationSpec.Limits(60,360000,360,2160000,2048,0,3600))
            || !settings.equals(new ContinuationSpec.Settings(16384,3,1024,900,160,"cl100k_base")))
            throw new IllegalArgumentException("full model, window and per-stage shared budget require review before changing");
        var ids = new HashSet<String>(); var kinds = new HashSet<String>();
        for (var task : tasks) {
            ContinuationSpec.identifier(task.id());
            if (!ids.add(task.id()) || task.agentInput().query()==null || task.agentInput().query().isBlank() || task.agentInput().workspaceFiles().isEmpty())
                throw new IllegalArgumentException("distinct tasks with public input required");
            for (var path : task.agentInput().workspaceFiles().keySet()) {
                ContinuationSpec.relativeFile(path);
                if (path.startsWith("preview/") || path.startsWith(".clawkit/") || path.startsWith("archive/")) throw new IllegalArgumentException("reserved initial path");
            }
            var environment=task.environment();
            if (environment==null || !Set.of("CONFIG_MIGRATION","CHANGING_EVIDENCE").contains(environment.kind()) || !kinds.add(environment.kind()))
                throw new IllegalArgumentException("one task of each actual workflow kind required");
            if (environment.kind().equals("CONFIG_MIGRATION") && (!environment.revisions().isEmpty() || environment.statusFile()!=null
                || environment.previewFiles().size()!=24)) throw new IllegalArgumentException("24 migration previews required");
            if (environment.kind().equals("CHANGING_EVIDENCE")) {
                ContinuationSpec.relativeFile(environment.statusFile());
                if (environment.revisions().size()!=3 || environment.previewFiles().size()!=20 || !task.agentInput().workspaceFiles().containsKey(environment.statusFile()))
                    throw new IllegalArgumentException("three actual revisions and twenty previews required");
                try {
                    if (!EvaluationArtifacts.JSON.readTree(task.agentInput().workspaceFiles().get(environment.statusFile())).equals(environment.revisions().getFirst()))
                        throw new IllegalArgumentException("initial public status differs from the environment initial state");
                } catch (java.io.IOException e) { throw new IllegalArgumentException("invalid initial state",e); }
                for (int i=0;i<3;i++) if (environment.revisions().get(i).path("revision").asInt()!=i+1 || environment.revisions().get(i).path("targets").size()!=10)
                    throw new IllegalArgumentException("ten targets per registered revision required");
            }
            var unique = new HashSet<String>();
            for (var path: environment.previewFiles()) { ContinuationSpec.relativeFile(path); if (!path.startsWith("preview/") || !unique.add(path)) throw new IllegalArgumentException("distinct preview paths required"); }
            var gold=task.gold();
            for (var path: gold.allOutputFiles()) { ContinuationSpec.relativeFile(path); if (!path.startsWith("preview/") || task.agentInput().workspaceFiles().containsKey(path)) throw new IllegalArgumentException("gold outputs must be new previews"); }
            if (!gold.allOutputFiles().containsAll(environment.previewFiles()) || !task.agentInput().workspaceFiles().keySet().containsAll(gold.unchangedFiles())
                || !task.agentInput().workspaceFiles().keySet().containsAll(gold.requiredReadTargets()) || gold.requiredFields().isEmpty()) throw new IllegalArgumentException("complete protected sources / gold required");
            gold.unchangedFiles().forEach(ContinuationSpec::relativeFile); gold.requiredReadTargets().forEach(ContinuationSpec::relativeFile);
            gold.forbiddenWriteTargets().forEach(ContinuationSpec::relativeFile);
            if (environment.statusFile()!=null && gold.unchangedFiles().contains(environment.statusFile())) throw new IllegalArgumentException("mutable fixture state is not a protected source");
        }
    }
    boolean acceptanceEnabled() { return version.equals("context-memory-live-full-acceptance-v2"); }
    List<Instance> instances() {
        var arms=List.of(ContinuationSpec.Arm.C1_ROLLING_SUMMARY,ContinuationSpec.Arm.C2_FROZEN_ORIGINAL_CONTEXT,ContinuationSpec.Arm.C3_CANDIDATE_CONTEXT);
        var result=new ArrayList<Instance>();
        for(int i=0;i<tasks.size();i++) for(int j=0;j<3;j++) { var arm=arms.get((i+j)%3); var task=tasks.get(i); result.add(new Instance(task.id()+"-"+arm.name().toLowerCase(Locale.ROOT),task.id(),arm)); }
        return List.copyOf(result);
    }
    Task task(Instance instance) { return tasks.stream().filter(t->t.id().equals(instance.taskId())).findFirst().orElseThrow(); }
}
