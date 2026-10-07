package com.clawkit.context.impl;

import com.clawkit.context.AdaptiveCompactionPolicy;
import com.clawkit.context.AnchorKind;
import com.clawkit.context.AnchorProvenance;
import com.clawkit.context.AnchorSnapshot;
import com.clawkit.context.CompactionAnchor;
import com.clawkit.context.CompactionHint;
import com.clawkit.context.Constraint;
import com.clawkit.context.ConstraintExtractor;
import com.clawkit.context.ContextBudgetPolicy;
import com.clawkit.context.Tokenizer;
import com.clawkit.tools.schema.Message;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Builds the bounded canonical anchor sidecar used by the compaction pipeline. */
final class AnchorSnapshotPlanner {
    static final String REQUIRED_ANCHORS_OVER_BUDGET = "REQUIRED_ANCHORS_OVER_BUDGET";

    record Plan(CompactionHint effectiveHint, AnchorSnapshot snapshot,
                List<String> retainedIds, String failureCode, int tokenCount) {
        boolean failed() { return failureCode != null; }
    }

    private final ConstraintExtractor extractor = new ConstraintExtractor();
    private final Tokenizer tokenizer;
    private final ContextBudgetPolicy budgetPolicy;
    private final AdaptiveCompactionPolicy adaptivePolicy;

    AnchorSnapshotPlanner(Tokenizer tokenizer, ContextBudgetPolicy budgetPolicy,
                          AdaptiveCompactionPolicy adaptivePolicy) {
        this.tokenizer = tokenizer;
        this.budgetPolicy = budgetPolicy;
        this.adaptivePolicy = adaptivePolicy;
    }

    Plan prepare(List<Message> rawMessages, CompactionHint requestedHint) {
        return prepare(rawMessages, requestedHint, adaptivePolicy.anchorBudgetTokens(budgetPolicy));
    }

    Plan prepare(List<Message> rawMessages, CompactionHint requestedHint, int budget) {
        if (budget < 1) throw new IllegalArgumentException("positive anchor budget required");
        CompactionHint hint = requestedHint != null ? requestedHint : CompactionHint.GENERAL;
        List<CompactionAnchor> merged = merge(hint.anchors(), legacyAnchors(rawMessages));
        if (merged.isEmpty()) {
            return new Plan(new CompactionHint(hint.profile(), List.of()),
                AnchorSnapshot.EMPTY, List.of(), null, 0);
        }

        List<CompactionAnchor> required = merged.stream()
            .filter(CompactionAnchor::required)
            .sorted(anchorOrder())
            .toList();
        if (required.size() > adaptivePolicy.maxAnchors()) {
            return failedPlan(hint, required);
        }

        AnchorSnapshot requiredSnapshot = render(hint, required);
        if (tokenizer.countTokens(requiredSnapshot.renderedText()) > budget) {
            return failedPlan(hint, required);
        }

        List<CompactionAnchor> selected = new ArrayList<>(required);
        List<CompactionAnchor> optional = merged.stream()
            .filter(anchor -> !anchor.required())
            .sorted(anchorOrder())
            .toList();
        for (CompactionAnchor anchor : optional) {
            if (selected.size() >= adaptivePolicy.maxAnchors()) break;
            List<CompactionAnchor> candidate = new ArrayList<>(selected);
            candidate.add(anchor);
            AnchorSnapshot candidateSnapshot = render(hint, candidate);
            if (tokenizer.countTokens(candidateSnapshot.renderedText()) <= budget) {
                selected.add(anchor);
            }
        }

        AnchorSnapshot snapshot = render(hint, selected);
        List<String> retained = selected.stream().map(CompactionAnchor::id).toList();
        return new Plan(new CompactionHint(hint.profile(), selected), snapshot,
            retained, null, tokenizer.countTokens(snapshot.renderedText()));
    }

    private Plan failedPlan(CompactionHint hint, List<CompactionAnchor> required) {
        CompactionHint effective = new CompactionHint(hint.profile(), required);
        AnchorSnapshot snapshot = render(hint, required);
        return new Plan(effective, snapshot, required.stream().map(CompactionAnchor::id).toList(),
            REQUIRED_ANCHORS_OVER_BUDGET, tokenizer.countTokens(snapshot.renderedText()));
    }

    private AnchorSnapshot render(CompactionHint hint, List<CompactionAnchor> anchors) {
        return AnchorSnapshot.render(new CompactionHint(hint.profile(), anchors),
            512, Math.max(adaptivePolicy.maxAnchors(), anchors.size()));
    }

    private List<CompactionAnchor> merge(List<CompactionAnchor> explicit,
                                         List<CompactionAnchor> legacy) {
        LinkedHashMap<String, CompactionAnchor> byId = new LinkedHashMap<>();
        for (CompactionAnchor anchor : legacy) byId.put(anchor.id(), anchor);
        for (CompactionAnchor anchor : explicit) {
            CompactionAnchor current = byId.get(anchor.id());
            if (current == null || !anchor.observedAt().isBefore(current.observedAt())) {
                byId.put(anchor.id(), anchor);
            }
        }
        return List.copyOf(byId.values());
    }

    private List<CompactionAnchor> legacyAnchors(List<Message> messages) {
        var byId = new LinkedHashMap<String, CompactionAnchor>();
        for (Message message : messages) {
            for (Constraint constraint : extractor.extract(List.of(message))) {
                CompactionAnchor candidate = legacyAnchor(constraint, message.role());
                CompactionAnchor current = byId.get(candidate.id());
                // A low-trust copy cannot replace a user constraint, regardless of message order.
                if (current == null || provenanceRank(candidate) < provenanceRank(current)) {
                    byId.put(candidate.id(), candidate);
                }
            }
        }
        return List.copyOf(byId.values());
    }

    private CompactionAnchor legacyAnchor(Constraint constraint, com.clawkit.tools.schema.Role role) {
        String text = constraint.text();
        String id = "legacy-" + shortHash(text);
        return switch (role) {
            case USER -> new CompactionAnchor(id, AnchorKind.USER_CONSTRAINT, text, null, true,
                CompactionAnchor.CONFIRMED, AnchorProvenance.USER, Instant.EPOCH);
            case TOOL -> new CompactionAnchor(id, AnchorKind.EVIDENCE, text, null, false,
                CompactionAnchor.OPEN, AnchorProvenance.TOOL_EVIDENCE, Instant.EPOCH);
            case ASSISTANT -> new CompactionAnchor(id, AnchorKind.OPEN_HYPOTHESIS, text, null, false,
                CompactionAnchor.OPEN, AnchorProvenance.MODEL_DERIVED, Instant.EPOCH);
            // System fragments are already protected. Regex text alone does not establish workflow facts.
            case SYSTEM -> new CompactionAnchor(id, AnchorKind.EVIDENCE, text, null, false,
                CompactionAnchor.OPEN, AnchorProvenance.WORKFLOW_STATE, Instant.EPOCH);
        };
    }

    private int provenanceRank(CompactionAnchor anchor) {
        return switch (anchor.provenance()) {
            case USER -> 0;
            case WORKFLOW_STATE -> 1;
            case TOOL_EVIDENCE -> 2;
            case MODEL_DERIVED -> 3;
        };
    }

    private Comparator<CompactionAnchor> anchorOrder() {
        return Comparator.comparingInt(this::priority)
            .thenComparing(CompactionAnchor::observedAt, Comparator.reverseOrder())
            .thenComparing(CompactionAnchor::id);
    }

    private int priority(CompactionAnchor anchor) {
        return switch (anchor.kind()) {
            case USER_CONSTRAINT, APPROVAL_BOUNDARY -> 0;
            case INCIDENT -> 1;
            case CONFIRMED_FACT, COUNTER_EVIDENCE, EVIDENCE -> 2;
            case OPEN_HYPOTHESIS, PENDING_CHECK -> 3;
        };
    }

    private String shortHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
