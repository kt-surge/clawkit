package com.clawkit.evaluation.context;

import com.clawkit.observability.RunReader;
import com.clawkit.observability.ToolCompletedPayload;
import com.clawkit.observability.ToolInvokedPayload;
import com.clawkit.observability.RunStartedPayload;
import com.clawkit.observability.RunCompletedPayload;
import com.clawkit.tools.schema.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Joins real tool events with opt-in raw model responses; proposed calls alone cannot satisfy an executed read. */
public final class ContinuationTraceReader {
    private ContinuationTraceReader() {}
    public static List<ContinuationTaskScorer.ToolObservation> read(Path home, Path instance, List<String> runIds) throws Exception {
        Map<String, ToolCall> proposed = proposals(instance.resolve("calls"));
        var observations = new ArrayList<ContinuationTaskScorer.ToolObservation>();
        var reader = new RunReader(home.resolve(".clawkit"));
        for (String runId : runIds) {
            var events = reader.readEvents(runId);
            if (!events.warnings().isEmpty()) throw new IllegalStateException("tool trace is incomplete or malformed");
            Map<String, ToolCompletedPayload> finished = new HashMap<>();
            events.value().forEach(event -> {
                if (event.payload() instanceof ToolCompletedPayload completed) finished.put(completed.toolCallId(), completed);
            });
            for (var event : events.value()) {
                if (!(event.payload() instanceof ToolInvokedPayload invoked)) continue;
                var proposal = proposed.get(runId + "/" + invoked.toolCallId());
                if (proposal == null || !invoked.toolName().equals(proposal.name())) {
                    throw new IllegalStateException("executed task tool has no matching raw proposal");
                }
                String path = proposal != null && proposal.arguments() != null ? proposal.arguments().path("path").asText(null) : null;
                var completed = finished.get(invoked.toolCallId());
                observations.add(new ContinuationTaskScorer.ToolObservation(runId, invoked.toolCallId(), invoked.toolName(), path,
                    completed != null && completed.success(), completed == null || !completed.success()));
            }
        }
        return List.copyOf(observations);
    }

    public static String taskStatus(Path home, String rootRunId) throws java.io.IOException {
        if (rootRunId == null) return "NOT_STARTED";
        var events = new RunReader(home.resolve(".clawkit")).readEvents(rootRunId);
        if (!events.warnings().isEmpty()) throw new IllegalStateException("root run trace is incomplete or malformed");
        boolean started = false;
        String status = null;
        for (var event : events.value()) {
            if (event.payload() instanceof RunStartedPayload && event.parentRunId() == null) started = true;
            if (event.payload() instanceof RunCompletedPayload completed) {
                if (status != null) throw new IllegalStateException("duplicate root completion");
                status = completed.status().name();
            }
        }
        if (!started) throw new IllegalStateException("root has no task start event");
        return status == null ? "INCOMPLETE" : status;
    }

    private static Map<String, ToolCall> proposals(Path calls) throws Exception {
        var proposed = new HashMap<String, ToolCall>();
        if (!Files.isDirectory(calls)) return proposed;
        try (var files = Files.list(calls)) {
            for (Path responseFile : files.filter(p -> p.getFileName().toString().endsWith("-response.json")).sorted().toList()) {
                String requestName = responseFile.getFileName().toString().replace("-response.json", "-request.json");
                var request = EvaluationArtifacts.JSON.readTree(calls.resolve(requestName).toFile());
                var response = EvaluationArtifacts.JSON.readTree(responseFile.toFile());
                for (var call : response.path("response").path("toolCalls")) {
                    var decoded = EvaluationArtifacts.JSON.treeToValue(call, ToolCall.class);
                    String key = request.path("runId").asText() + "/" + decoded.id();
                    if (proposed.putIfAbsent(key, decoded) != null) throw new IllegalStateException("duplicate tool call identity in raw trace");
                }
            }
        }
        return proposed;
    }
}
