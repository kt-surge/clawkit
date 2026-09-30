package com.clawkit.ops.loop.managed;

import java.time.Clock;
import java.util.*;

/** Model hypotheses are inferences, kept separately from observations and execution permission. */
public record DiagnosticReport(String summary,List<Hypothesis> hypotheses) {
    public static final class ContractViolation extends IllegalArgumentException {
        ContractViolation(String message) { super(message); }
    }
    public enum Cause { DEPENDENCY_FAILURE, CONFIGURATION_MISMATCH, RESOURCE_EXHAUSTION, APPLICATION_FAILURE, SELF_RECOVERY, UNKNOWN }
    public enum Assessment { SUPPORTED, SUSPECTED, UNKNOWN }
    public record Hypothesis(String id,Cause cause,Assessment assessment,String explanation,
                             List<String> supportRefs,List<String> counterRefs,List<String> missingEvidence,
                             List<String> alternativeIds,List<ManagedObserver.Probe> nextProbes) {
        public Hypothesis {
            if (id==null || !id.matches("[a-zA-Z][a-zA-Z0-9_-]{0,62}"))
                throw new ContractViolation("hypothesis id must be a bounded alphanumeric label");
            Objects.requireNonNull(cause); Objects.requireNonNull(assessment);
            text(explanation,1000);
            supportRefs=refs(supportRefs,12); counterRefs=refs(counterRefs,12);
            missingEvidence=List.copyOf(missingEvidence); alternativeIds=refs(alternativeIds,8); nextProbes=List.copyOf(nextProbes);
            if (missingEvidence.size()>8 || nextProbes.size()>8 || nextProbes.stream().distinct().count()!=nextProbes.size())
                throw new ContractViolation("bounded diagnostic gaps/probes required");
            missingEvidence.forEach(gap -> text(gap,250));
            // One source record may contain both clues and contrary facts (e.g. a finite memory limit,
            // low current usage and OOMKilled=false). Do not force the model to hide part of that record.
            if (alternativeIds.contains(id)
                    || assessment==Assessment.SUPPORTED && supportRefs.isEmpty()
                    || assessment==Assessment.UNKNOWN && missingEvidence.isEmpty())
                throw new ContractViolation("SUPPORTED needs support references; UNKNOWN needs missing evidence; alternatives cannot reference self");
        }
    }
    public DiagnosticReport {
        text(summary,1000); hypotheses=List.copyOf(hypotheses);
        if (hypotheses.isEmpty() || hypotheses.size()>8 || hypotheses.stream().map(Hypothesis::id).distinct().count()!=hypotheses.size())
            throw new ContractViolation("one to eight distinct hypotheses required");
        Set<String> ids=new HashSet<>(); hypotheses.forEach(h -> ids.add(h.id()));
        if (hypotheses.stream().anyMatch(h -> !ids.containsAll(h.alternativeIds())))
            throw new ContractViolation("alternative hypothesis must exist in the report");
    }
    public void validate(List<DecisionEvidence> evidence,Clock clock) {
        Map<String,DecisionEvidence> byId=new HashMap<>(); evidence.forEach(e -> byId.put(e.id(),e));
        Set<String> reportRefs=new HashSet<>(); hypotheses.forEach(h -> { reportRefs.addAll(h.supportRefs()); reportRefs.addAll(h.counterRefs()); });
        for (ManagedObserver.Probe probe:List.of(ManagedObserver.Probe.SERVICE,ManagedObserver.Probe.HEALTH,ManagedObserver.Probe.BUSINESS,ManagedObserver.Probe.DEPENDENCIES)) {
            var current=evidence.stream().filter(e -> e.observation().probe()==probe && e.currentAt(clock.instant())).toList();
            var statuses=current.stream().map(e -> e.observation().status()).distinct().toList();
            if (statuses.size()>1 && statuses.stream().anyMatch(s -> current.stream().noneMatch(e -> e.observation().status()==s && reportRefs.contains(e.id()))))
                throw new IllegalArgumentException("conflicting current observations must be represented in the diagnosis");
        }
        for (Hypothesis h:hypotheses) {
            var cited=new ArrayList<DecisionEvidence>();
            for (String id:java.util.stream.Stream.concat(h.supportRefs().stream(),h.counterRefs().stream()).toList()) {
                var item=byId.get(id);
                if (item==null) throw new IllegalArgumentException("unknown diagnostic evidence reference");
                if (!item.currentAt(clock.instant()))
                    throw new IllegalArgumentException("diagnostic reference is stale: "+item.id()+"; refresh this probe or describe a gap");
                if (item.envelope()==null || item.envelope().quality()!=EvidenceEnvelope.Quality.COMPLETE)
                    throw new IllegalArgumentException("diagnostic reference is incomplete/legacy: "+item.id()+" quality="
                        +(item.envelope()==null ? "LEGACY" : item.envelope().quality())
                        +"; remove this id from supportRefs/counterRefs and describe the unavailable source in missingEvidence");
                cited.add(item);
            }
            if (!cited.isEmpty()) {
                var first=cited.stream().map(e -> e.observation().observedAt()).min(Comparator.naturalOrder()).orElseThrow();
                var last=cited.stream().map(e -> e.observation().observedAt()).max(Comparator.naturalOrder()).orElseThrow();
                if (java.time.Duration.between(first,last).compareTo(java.time.Duration.ofSeconds(30))>0)
                    throw new IllegalArgumentException("diagnostic references span incompatible source times");
            }
            if (h.cause()==Cause.CONFIGURATION_MISMATCH && h.assessment()==Assessment.SUPPORTED
                    && h.supportRefs().stream().map(byId::get).allMatch(e -> e.observation().probe()==ManagedObserver.Probe.CHANGES))
                throw new IllegalArgumentException("change timing alone supports correlation, not configuration cause");
        }
    }
    private static List<String> refs(List<String> refs,int max) {
        refs=List.copyOf(refs);
        if (refs.size()>max || refs.stream().distinct().count()!=refs.size() || refs.stream().anyMatch(r -> r.isBlank() || r.length()>100))
            throw new ContractViolation("bounded distinct diagnostic references required");
        return refs;
    }
    private static void text(String text,int max) {
        if (text==null || text.isBlank() || text.length()>max) throw new ContractViolation("bounded diagnostic text required");
    }
}
