package com.clawkit.evaluation.context;

import com.clawkit.observability.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Regrades frozen files, raw responses and saved tool events without trusting summary totals or recorded scores. */
public final class ContinuationAudit {
    private record EventFact(long sequence, java.time.Instant occurredAt, String runId, String parentRunId,
                             Integer turnNumber, RunEventPayload payload) {
        static EventFact of(RunEventEnvelope event) {
            return new EventFact(event.sequence(), event.occurredAt(), event.runId(), event.parentRunId(), event.turnNumber(), event.payload());
        }
    }
    public record Result(int planned, int attempted, int passed, int notRun, boolean artifactIntegrity,
                         boolean outcomesAgree, List<Map<String, Object>> recomputed) {}
    public static final String COMPARISON_VERSION = "OUTCOME_CANONICAL_FAILURE_MULTISET_V2";
    private ContinuationAudit() {}
    public static Result recompute(Path root) throws Exception {
        root = root.toAbsolutePath().normalize();
        var frozen = EvaluationArtifacts.JSON.readTree(root.resolve("frozen-data.json").toFile());
        var spec = ContinuationSpec.parseArchived(frozen);
        var manifest = EvaluationArtifacts.JSON.readTree(root.resolve("manifest.json").toFile());
        var expectedHashes = EvaluationArtifacts.JSON.readValue(root.resolve("artifact-hashes.json").toFile(),
            new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
        boolean integrity = expectedHashes.equals(EvaluationArtifacts.hashes(root))
            && manifest.path("datasetHash").asText().equals(EvaluationArtifacts.sha256(Files.readAllBytes(root.resolve("frozen-data.json"))))
            && EvaluationArtifacts.JSON.readTree(root.resolve("source-integrity.json").toFile()).path("unchanged").asBoolean(false);
        var sourceFields = manifest.path("sourceHashes").fields();
        while (sourceFields.hasNext()) {
            var entry = sourceFields.next();
            ContinuationSpec.relativeFile(entry.getKey());
            integrity &= entry.getValue().asText().equals(EvaluationArtifacts.sha256(Files.readAllBytes(root.resolve("source-inputs/" + entry.getKey()))));
        }
        var rows = new LinkedHashMap<String, JsonNode>();
        for (String line : Files.readAllLines(root.resolve("instances.jsonl"))) if (!line.isBlank()) {
            var row = EvaluationArtifacts.JSON.readTree(line);
            if (rows.putIfAbsent(row.path("id").asText(), row) != null) throw new IllegalStateException("duplicate recorded instance");
        }
        if (!rows.keySet().equals(spec.instances().stream().map(ContinuationSpec.Instance::id).collect(java.util.stream.Collectors.toSet()))) {
            throw new IllegalStateException("planned denominator differs from archived rows");
        }
        boolean agrees = true;
        int attempted = 0, passed = 0, notRun = 0;
        var results = new ArrayList<Map<String, Object>>();
        for (var instance : spec.instances()) {
            var row = rows.get(instance.id());
            Path directory = root.resolve("instances/" + instance.id());
            if (row.path("status").asText().equals("NOT_RUN")) {
                if (Files.exists(directory.resolve("calls"))) throw new IllegalStateException("unstarted row contains model requests");
                notRun++; results.add(Map.of("id", instance.id(), "status", "NOT_RUN", "reason", row.path("failureType").asText())); continue;
            }
            attempted++;
            Path home = root.resolve("agents/" + instance.id() + "/home");
            Path workspace = root.resolve("agents/" + instance.id() + "/workspace");
            Path runs = home.resolve(".clawkit/runs");
            var runIds = new ArrayList<String>();
            if (Files.isDirectory(runs)) try (var paths = Files.list(runs)) {
                paths.filter(Files::isDirectory).sorted().forEach(path -> runIds.add(path.getFileName().toString()));
            }
            var reader = new RunReader(home.resolve(".clawkit"));
            var events = new ArrayList<RunEventEnvelope>();
            for (var id : runIds) {
                var read = reader.readEvents(id);
                if (!read.warnings().isEmpty()) throw new IllegalStateException("incomplete archived run events");
                events.addAll(read.value());
            }
            var allEvents = new ArrayList<RunEventEnvelope>();
            var codec = new RunEventCodec(EvaluationArtifacts.JSON);
            var sequences = new HashMap<String, Long>();
            for (var line : Files.readAllLines(directory.resolve("runtime-events.jsonl"))) if (!line.isBlank()) {
                var event = codec.deserialize(line);
                if (event.payload() instanceof UnknownEventPayload || event.schemaVersion() != RunEventEnvelope.CURRENT_SCHEMA_VERSION
                    || event.sequence() != sequences.merge(event.runId(), 1L, Long::sum)) {
                    throw new IllegalStateException("all-phase event trace is incomplete or malformed");
                }
                allEvents.add(event);
            }
            for (var id : runIds) {
                if (!events.stream().filter(e -> e.runId().equals(id)).map(EventFact::of).toList()
                    .equals(allEvents.stream().filter(e -> e.runId().equals(id)).map(EventFact::of).toList())) {
                    throw new IllegalStateException("lifecycle trace differs from all-phase trace");
                }
            }
            String taskRoot = events.stream().filter(e -> e.payload() instanceof RunStartedPayload && e.parentRunId() == null)
                .min(Comparator.comparing(RunEventEnvelope::occurredAt)).map(RunEventEnvelope::runId).orElse(null);
            var tools = ContinuationTraceReader.read(home, directory, runIds);
            var task = spec.task(instance);
            var score = ContinuationTaskScorer.score(workspace, task.outcomeGold(), task.agentInput().workspaceFiles(), tools,
                ContinuationTraceReader.taskStatus(home, taskRoot));
            var ledger = new ArrayList<UsageLedger.Entry>();
            boolean reserveViolation = false;
            boolean requestContract = true;
            if (Files.isDirectory(directory.resolve("calls"))) try (var calls = Files.list(directory.resolve("calls"))) {
                for (var file : calls.filter(p -> p.getFileName().toString().endsWith("-response.json")).sorted().toList()) {
                    var raw = EvaluationArtifacts.JSON.readTree(file.toFile());
                    var entry = EvaluationArtifacts.JSON.treeToValue(raw.path("usageEntry"), UsageLedger.Entry.class);
                    ledger.add(entry);
                    var request = EvaluationArtifacts.JSON.readTree(file.resolveSibling(file.getFileName().toString()
                        .replace("-response.json", "-request.json")).toFile());
                    var parameters = request.path("parameters");
                    requestContract &= parameters.path("maxTokens").asInt() > 0
                        && parameters.path("maxTokens").asInt() <= spec.limits().outputTokensPerCall()
                        && parameters.path("temperature").isNumber() && parameters.path("temperature").asDouble() == 0
                        && parameters.path("reasoningMode").asText().equals("DISABLED")
                        && parameters.path("stream").isBoolean() && !parameters.path("stream").asBoolean();
                    for (var tool : request.path("tools")) requestContract &= FixedToolEvaluationGateway.TASK_TOOLS.contains(tool.path("name").asText());
                    long upper = EvaluationArtifacts.JSON.writeValueAsBytes(Map.of("messages", request.path("messages"), "tools", request.path("tools"))).length
                        + (long) spec.settings().framingReserveTokens() + spec.limits().outputTokensPerCall();
                    reserveViolation |= entry.usage().source() == com.clawkit.provider.UsageSource.ACTUAL && entry.usage().totalTokens() > upper;
                }
            }
            var usage = UsageLedger.total(ledger);
            long dispatched = allEvents.stream().filter(e -> e.payload() instanceof ProviderCallStartedPayload).count();
            boolean accountingUnknown = dispatched != usage.actualCalls() || reserveViolation;
            boolean success = score.taskCompleted() && !accountingUnknown && row.path("failureType").isNull()
                && !row.path("accountingInvalid").asBoolean(false);
            if (success) passed++;
            boolean outcomeMatches = score.equals(EvaluationArtifacts.JSON.treeToValue(row.path("outcome"), ContinuationTaskScorer.Result.class));
            // JSON may decode a small long as IntNode; compare typed totals instead of numeric node classes.
            boolean usageMatches = usage.equals(EvaluationArtifacts.JSON.treeToValue(row.path("usage"), UsageLedger.Totals.class));
            boolean statusMatches = success == row.path("status").asText().equals("PASS")
                && dispatched == row.path("dispatchedProviderCalls").asLong();
            boolean withinLimits = dispatched <= spec.limits().instanceProviderCalls() && tools.size() <= spec.settings().instanceToolCalls();
            agrees &= requestContract && withinLimits && outcomeMatches && usageMatches && statusMatches;
            var result = new LinkedHashMap<String, Object>();
            result.put("id", instance.id()); result.put("auditComparisonVersion", COMPARISON_VERSION);
            result.put("taskCompleted", score.taskCompleted()); result.put("passed", success);
            result.put("outcome", score); result.put("usage", usage); result.put("dispatchedProviderCalls", dispatched);
            result.put("accountingUnknown", accountingUnknown); result.put("requestContract", requestContract);
            result.put("withinLimits", withinLimits); result.put("outcomeMatches", outcomeMatches);
            result.put("usageMatches", usageMatches); result.put("statusMatches", statusMatches);
            result.put("retrievalFactSupport", "NOT_SCORED_DERIVATION_IS_NOT_FACT_SUPPORT");
            results.add(Collections.unmodifiableMap(result));
        }
        return new Result(spec.instances().size(), attempted, passed, notRun, integrity, agrees, List.copyOf(results));
    }
}
