package com.clawkit.evaluation.context;

import com.clawkit.context.CompactionAnchor;
import com.clawkit.tools.schema.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fixture scripts and gold are never members of AgentInput. */
public record ReplayCase(String id, String family, Operation operation, AgentInput input,
                         List<String> script, Gold gold) {
    public enum Operation { COMPACT, SESSION_RESUME, MEMORY, FRESHNESS, BUDGET_FAILURE }
    public record HistorySource(String id, int revision, Instant observedAt, List<Message> messages) {}
    public record AgentInput(List<HistorySource> histories, String query, int contextWindow,
                             double anchorRatio, List<CompactionAnchor> anchors, Map<String, String> workspaceFiles) {}
    public record Check(String artifact, String pointer, String relation, JsonNode expected) {}
    public record Gold(List<Check> checks, List<Set<String>> evidenceAlternatives, int retrievalK) {}
}
