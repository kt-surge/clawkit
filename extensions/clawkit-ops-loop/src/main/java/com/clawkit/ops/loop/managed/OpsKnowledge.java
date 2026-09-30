package com.clawkit.ops.loop.managed;

import java.time.Instant;
import java.util.*;

/** Versioned advisory knowledge. None of these records grants execution permission. */
public final class OpsKnowledge {
    private OpsKnowledge() {}
    public enum State { DRAFT, REVIEWED, REVOKED }
    public enum OutcomeKind { INDEPENDENT_RECOVERY, HUMAN_HANDOFF }
    public record Scope(String environment,String service,long applicationVersion) {
        public Scope { ManagedApplication.identifier(environment); ManagedApplication.identifier(service); if(applicationVersion<1) throw new IllegalArgumentException("positive application version required"); }
        public static Scope of(ManagedApplication app) { return new Scope(app.composeProject(),app.service(),app.version()); }
        public boolean matches(ManagedApplication app) { return equals(of(app)); }
    }
    public record Reference(String id,int version,String contentHash) {
        public Reference { ManagedApplication.identifier(id); if(version<1 || version>10000) throw new IllegalArgumentException("bounded knowledge version required"); hash(contentHash); }
        public String key() { return id+"-v"+version; }
    }
    public record ProbeCondition(ManagedObserver.Probe probe,ManagedObserver.Status status) {
        public ProbeCondition { Objects.requireNonNull(probe); Objects.requireNonNull(status); }
    }
    public record Conditions(List<ProbeCondition> statuses,List<ManagedObserver.Probe> requiredProbes,
                             Boolean oomKilled,boolean forbidTruncated) {
        public Conditions {
            statuses=List.copyOf(statuses); requiredProbes=List.copyOf(requiredProbes);
            if(statuses.size()>8 || requiredProbes.size()>8 || statuses.stream().map(ProbeCondition::probe).distinct().count()!=statuses.size()
                    || requiredProbes.stream().distinct().count()!=requiredProbes.size()) throw new IllegalArgumentException("bounded unique knowledge conditions required");
            if(statuses.isEmpty() && requiredProbes.isEmpty() && oomKilled==null) throw new IllegalArgumentException("knowledge needs evidence conditions");
        }
    }
    public record RunbookVersion(String id,int version,Scope scope,String title,String symptoms,
            Conditions conditions,List<String> applicability,List<String> prohibitions,List<ManagedObserver.Probe> probes,
            OpsDecision.Disposition disposition,OpsDecision.Playbook playbook,List<String> verification,
            List<String> sourceCaseIds,Instant createdAt) {
        public RunbookVersion {
            ManagedApplication.identifier(id); if(version<1 || version>10000) throw new IllegalArgumentException("bounded knowledge version required");
            if(id.startsWith("case-")) throw new IllegalArgumentException("case prefix is reserved for case references");
            Objects.requireNonNull(scope); text(title,150); text(symptoms,800); Objects.requireNonNull(conditions);
            applicability=texts(applicability,8,250); prohibitions=texts(prohibitions,8,250); verification=texts(verification,8,250);
            probes=List.copyOf(probes); sourceCaseIds=List.copyOf(sourceCaseIds); Objects.requireNonNull(createdAt); Objects.requireNonNull(disposition);
            if(probes.size()>8 || probes.stream().distinct().count()!=probes.size() || sourceCaseIds.size()>12) throw new IllegalArgumentException("bounded runbook references required");
            sourceCaseIds.forEach(ManagedApplication::identifier);
            if((disposition==OpsDecision.Disposition.PROPOSE_ACTION)!=(playbook!=null) || disposition==OpsDecision.Disposition.INVESTIGATE
                    || applicability.isEmpty() || prohibitions.isEmpty() || verification.isEmpty()) throw new IllegalArgumentException("explicit applicability, exclusions and verification required");
            if(playbook!=null && (!conditions.forbidTruncated() || !Boolean.FALSE.equals(conditions.oomKilled())
                    || !conditions.requiredProbes().contains(ManagedObserver.Probe.RESOURCES)))
                throw new IllegalArgumentException("repair knowledge must require complete resources and exclude OOM/truncation");
        }
        public Reference reference() { return new Reference(id,version,ManagedContracts.hash(this)); }
    }
    public record EvidenceReference(String id,String contentHash) {
        public EvidenceReference { text(id,100); hash(contentHash); }
    }
    public record OpsCase(String id,Scope scope,String incidentId,Instant createdAt,OutcomeKind outcome,
            String impact,DiagnosticReport proposedDiagnosis,List<EvidenceReference> evidence,
            String decisionArtifactHash,String outcomeArtifactHash,String sourceHash) {
        public OpsCase {
            ManagedApplication.identifier(id); Objects.requireNonNull(scope); ManagedApplication.identifier(incidentId);
            Objects.requireNonNull(createdAt); Objects.requireNonNull(outcome); text(impact,1000); evidence=List.copyOf(evidence);
            if(evidence.size()>64) throw new IllegalArgumentException("bounded case evidence required"); hash(decisionArtifactHash); hash(outcomeArtifactHash); hash(sourceHash);
        }
    }
    public record CaseReview(String caseId,String contentHash,State state,DiagnosticReport.Cause confirmedCause,
                             String operator,String note,Instant at) {
        public CaseReview { ManagedApplication.identifier(caseId); hash(contentHash); Objects.requireNonNull(state); text(operator,100); text(note,500); Objects.requireNonNull(at);
            if(state==State.REVIEWED && confirmedCause==null) throw new IllegalArgumentException("human confirmed cause required"); }
    }
    public record ReplaySample(String id,ManagedApplication application,Instant at,List<DecisionEvidence> evidence,boolean expectedApplicable) {
        public ReplaySample { ManagedApplication.identifier(id); Objects.requireNonNull(application); Objects.requireNonNull(at); evidence=List.copyOf(evidence); if(evidence.size()>32) throw new IllegalArgumentException("bounded replay evidence required"); }
    }
    public record ReplayResult(String sampleId,boolean expectedApplicable,boolean applicable,boolean passed) {
        public ReplayResult { ManagedApplication.identifier(sampleId); if(passed!=(expectedApplicable==applicable)) throw new IllegalArgumentException("inconsistent replay result"); }
    }
    public record ReplayReport(String id,Reference knowledge,String sampleVersion,String sampleHash,Instant at,List<ReplayResult> results) {
        public ReplayReport { ManagedApplication.identifier(id); Objects.requireNonNull(knowledge); text(sampleVersion,100); hash(sampleHash); Objects.requireNonNull(at); results=List.copyOf(results);
            if(results.size()<2 || results.size()>24 || results.stream().map(ReplayResult::sampleId).distinct().count()!=results.size()) throw new IllegalArgumentException("bounded unique replay results required"); }
        public boolean qualified() { return results.size()>=2 && results.stream().allMatch(ReplayResult::passed)
            && results.stream().anyMatch(ReplayResult::expectedApplicable) && results.stream().anyMatch(r -> !r.expectedApplicable()); }
    }
    public record QualificationReview(Reference knowledge,State state,String replayId,String replayHash,
            String operator,String note,Instant at) {
        public QualificationReview { Objects.requireNonNull(knowledge); Objects.requireNonNull(state); text(operator,100); text(note,500); Objects.requireNonNull(at);
            if(state==State.REVIEWED) { ManagedApplication.identifier(replayId); hash(replayHash); } }
    }
    public record Match(Reference reference,String title,double score,String matchReason,boolean applicable,RunbookVersion runbook) {}
    public record CaseMatch(String id,String contentHash,DiagnosticReport.Cause confirmedCause,OutcomeKind outcome,String impact,double score) {}
    public record SearchResult(List<Match> runbooks,List<CaseMatch> cases) {
        public SearchResult { runbooks=List.copyOf(runbooks); cases=List.copyOf(cases); }
        public static SearchResult empty() { return new SearchResult(List.of(),List.of()); }
    }
    static void text(String value,int cap) { if(value==null || value.isBlank() || value.length()>cap) throw new IllegalArgumentException("bounded knowledge text required"); }
    static List<String> texts(List<String> value,int count,int cap) { value=List.copyOf(value); if(value.size()>count) throw new IllegalArgumentException("bounded knowledge list required"); value.forEach(s -> text(s,cap)); return value; }
    static void hash(String value) { if(value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("knowledge content hash required"); }
}
