package com.clawkit.engine.impl;

import com.clawkit.tools.ToolExecutionResult;
import com.clawkit.tools.ToolExecutionStatus;
import com.clawkit.tools.ToolMetadataProvenance.ToolMetadataSource;
import com.clawkit.tools.action.Digests;
import com.clawkit.tools.action.EffectCertainty;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;

/** Advisory from executed native read batches; never a completion or freshness decision. */
final class ReadBatchRepeatTracker {
    private static final int THRESHOLD = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int READ_PHASE_THRESHOLD = 6;
    private static final int MAX_RECENT_BATCHES = 12;
    private record BatchFingerprint(String request,String returned) {}
    private final LinkedHashMap<String,String> recentBatches = new LinkedHashMap<>();
    private String lastSignature;
    private int readPhaseBatches;
    private boolean advisoryTriggered;
    private boolean rotatingWarning;
    private int consecutive;
    private boolean warningPending;

    void observe(List<ToolCall> calls, List<ToolExecutionResult> results) {
        BatchFingerprint fingerprint = signature(calls, results);
        if (fingerprint == null) { reset(); return; }
        String previous = recentBatches.get(fingerprint.request());
        // A changed returned value invalidates the read phase, even if other groups were stable.
        if (previous != null && !previous.equals(fingerprint.returned())) { reset(); previous = null; }
        boolean revisited = fingerprint.returned().equals(previous);
        recentBatches.remove(fingerprint.request());
        recentBatches.put(fingerprint.request(), fingerprint.returned());
        while (recentBatches.size() > MAX_RECENT_BATCHES) recentBatches.remove(recentBatches.keySet().iterator().next());
        readPhaseBatches = Math.min(READ_PHASE_THRESHOLD, readPhaseBatches + 1);
        if (fingerprint.returned().equals(lastSignature)) consecutive = Math.min(THRESHOLD + 1, consecutive + 1);
        else { lastSignature = fingerprint.returned(); consecutive = 1; }
        if (!advisoryTriggered && (consecutive == THRESHOLD || (readPhaseBatches >= READ_PHASE_THRESHOLD && revisited))) {
            rotatingWarning = consecutive < THRESHOLD;
            advisoryTriggered = true; warningPending = true;
        }
    }

    String takeWarning() {
        if (!warningPending) return null;
        warningPending = false;
        String observation = rotatingWarning
            ? "Six or more successful native read-only batches occurred in this read phase, and the latest batch revisited the same arguments and returned text. "
            : "Three consecutive successful native read-only batches used the same arguments and returned identical text. ";
        return "[Runtime][Repeated Read Batch] " + observation + "Review the remaining task and needed "
            + "checks before another reread. Required polling and fresh verification remain allowed. "
            + "If the requested work is finished, submit completion for configured acceptance; otherwise "
            + "identify the unresolved condition. This observation does not prove current freshness, "
            + "business correctness, or task completion. It grants no permissions.";
    }

    private void reset() {
        lastSignature = null; consecutive = 0; warningPending = false; readPhaseBatches = 0;
        advisoryTriggered = false; rotatingWarning = false; recentBatches.clear();
    }

    private static BatchFingerprint signature(List<ToolCall> calls, List<ToolExecutionResult> results) {
        // Existing single-call loop detection remains separate.
        if (calls == null || results == null || calls.size() < 2 || calls.size() != results.size()) return null;
        var byId = new HashMap<String, ToolExecutionResult>();
        for (ToolExecutionResult result : results) {
            if (result == null || result.toolCallId() == null || byId.put(result.toolCallId(), result) != null) return null;
        }
        var parts = new ArrayList<String>();
        var requests = new ArrayList<String>();
        for (ToolCall call : calls) {
            if (call == null || call.arguments() == null) return null;
            ToolExecutionResult result = byId.remove(call.id());
            if (result == null || !call.name().equals(result.toolName())
                || result.status() != ToolExecutionStatus.SUCCESS
                || result.effectCertainty() != EffectCertainty.NO_EFFECT_CONFIRMED || result.output() == null) return null;
            var metadata = result.metadata();
            if (metadata == null || !metadata.isReadOnly() || !call.name().equals(metadata.name())
                || metadata.provenance() == null || !metadata.provenance().trusted()
                || metadata.provenance().source() != ToolMetadataSource.BUILTIN
                || !call.name().equals(metadata.provenance().sourceId())) return null;
            requests.add(digest(call.name() + "\n" + canonical(call.arguments())));
            parts.add(digest(call.name() + "\n" + canonical(call.arguments()) + "\n"
                + digest(result.output()) + "\n" + result.truncated()));
        }
        if (!byId.isEmpty()) return null;
        parts.sort(String::compareTo);
        requests.sort(String::compareTo);
        return new BatchFingerprint(digest(String.join("\n", requests)), digest(String.join("\n", parts)));
    }

    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            var object = JSON.createObjectNode();
            value.properties().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> object.set(entry.getKey(), canonical(entry.getValue())));
            return object;
        }
        if (value.isArray()) {
            var array = JSON.createArrayNode(); value.forEach(item -> array.add(canonical(item))); return array;
        }
        return value;
    }

    private static String digest(String text) { return Digests.sha256Hex(text.getBytes(StandardCharsets.UTF_8)); }
}
