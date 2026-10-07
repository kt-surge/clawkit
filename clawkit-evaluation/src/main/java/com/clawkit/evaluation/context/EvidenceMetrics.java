package com.clawkit.evaluation.context;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Source-level retrieval metrics. Alternative complete source sets are evaluator-only. */
public final class EvidenceMetrics {
    private EvidenceMetrics() {}

    public record Result(int uniqueReturned, Double sourceRecall, Boolean allEvidenceHit,
                         Boolean anyEvidenceHit, Double precision, boolean unanswerable,
                         boolean emptyRetrieval) {}

    public static Result score(List<String> rankedSources, int k, List<Set<String>> alternatives) {
        if (k < 1) throw new IllegalArgumentException("positive k required");
        var returned = new LinkedHashSet<String>();
        rankedSources.stream().distinct().limit(k).forEach(returned::add);
        if (alternatives.isEmpty()) {
            return new Result(returned.size(), null, null, null, null, true, returned.isEmpty());
        }
        if (alternatives.stream().anyMatch(Set::isEmpty)) {
            throw new IllegalArgumentException("empty evidence set is not an answerable case");
        }
        Set<String> relevant = new LinkedHashSet<>();
        alternatives.forEach(relevant::addAll);
        long hits = returned.stream().filter(relevant::contains).count();
        double recall = alternatives.stream().mapToDouble(required ->
            required.stream().filter(returned::contains).count() / (double) required.size())
            .max().orElseThrow();
        return new Result(returned.size(), recall,
            alternatives.stream().anyMatch(returned::containsAll), hits > 0,
            returned.isEmpty() ? null : hits / (double) returned.size(), false, returned.isEmpty());
    }
}
