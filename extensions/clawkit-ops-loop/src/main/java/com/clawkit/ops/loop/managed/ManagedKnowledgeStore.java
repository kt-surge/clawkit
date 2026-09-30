package com.clawkit.ops.loop.managed;

import com.clawkit.memory.impl.KeywordScorer;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.clawkit.ops.loop.managed.OpsKnowledge.*;
import static java.nio.file.StandardOpenOption.*;

/** Immutable bodies/replays and auditable human reviews. State changes never write application policy. */
public final class ManagedKnowledgeStore implements KnowledgeAccess {
    public record ReplayInput(String sampleVersion,List<ReplaySample> samples) {
        public ReplayInput { OpsKnowledge.text(sampleVersion,100); samples=List.copyOf(samples);
            if(samples.size()<2 || samples.size()>24 || samples.stream().map(ReplaySample::id).distinct().count()!=samples.size())
                throw new IllegalArgumentException("two to twenty-four unique replay samples required"); }
    }
    public record RunbookEntry(RunbookVersion runbook,State state,QualificationReview review) {}
    public record CaseEntry(OpsCase opsCase,State state,CaseReview review) {}
    public record CaseSource(ManagedApplication application,ManagedIncident incident,ManagedIncidentController.DecisionRecord decision,
                             String decisionArtifactHash,String outcomeArtifactHash) {}
    private final Path root;
    private final Clock clock;
    private final ManagedControlStore files;
    public ManagedKnowledgeStore(Path root,Clock clock) throws IOException { this.root=root.toAbsolutePath().normalize(); this.clock=Objects.requireNonNull(clock); files=new ManagedControlStore(this.root); }

    public Reference importRunbook(ManagedApplication app,RunbookVersion book) throws Exception {
        if(!book.scope().matches(app) || book.createdAt().isAfter(clock.instant())) throw new IllegalArgumentException("runbook scope/time differs from registered application");
        return files.locked(() -> { var ref=book.reference(); Path path=body(ref);
            if(Files.exists(path)) { if(!read(path,RunbookVersion.class,16384).equals(book)) throw new IllegalArgumentException("knowledge version is immutable; use a new version"); return ref; }
            if(runbooks().size()>=128) throw new IOException("knowledge version cap reached");
            if(ManagedContracts.JSON.writeValueAsBytes(book).length>16384) throw new IllegalArgumentException("knowledge body exceeds byte cap");
            for(String id:book.sourceCaseIds()) requireReviewedCase(id,book.scope());
            files.write(path.getFileName().toString(),book); return ref;
        });
    }
    public ReplayReport replay(Reference ref,ReplayInput input) throws Exception {
        return files.locked(() -> { var book=requireBody(ref);
            var results=input.samples().stream().map(sample -> { boolean applicable=applicable(book,sample.application(),sample.evidence(),Clock.fixed(sample.at(),ZoneOffset.UTC));
                return new ReplayResult(sample.id(),sample.expectedApplicable(),applicable,applicable==sample.expectedApplicable()); }).toList();
            var report=new ReplayReport("replay-"+UUID.randomUUID(),ref,input.sampleVersion(),ManagedContracts.hash(input),clock.instant(),results);
            if(ManagedContracts.JSON.writeValueAsBytes(input).length>262144) throw new IllegalArgumentException("replay input exceeds byte cap");
            files.write("samples-"+report.id()+".json",input);
            files.write(report.id()+".json",report); return report;
        });
    }
    public QualificationReview review(Reference ref,String replayId,String operator,String note) throws Exception {
        return files.locked(() -> { var book=requireBody(ref); var prior=reviewOf(ref);
            if(prior!=null && prior.state()==State.REVOKED) throw new IllegalArgumentException("revoked version cannot be reactivated; create a new version");
            ManagedApplication.identifier(replayId); var replay=read(root.resolve(replayId+".json"),ReplayReport.class,16384);
            if(!replay.knowledge().equals(ref) || !replay.qualified()) throw new IllegalArgumentException("matching positive/negative replay must pass before human review");
            requireReplay(book,replay);
            for(String id:book.sourceCaseIds()) requireReviewedCase(id,book.scope());
            var value=new QualificationReview(ref,State.REVIEWED,replay.id(),ManagedContracts.hash(replay),operator,note,clock.instant());
            saveReview(ref,value); return value;
        });
    }
    public QualificationReview revoke(Reference ref,String operator,String note) throws Exception {
        return files.locked(() -> { requireBody(ref); var prior=reviewOf(ref);
            var value=new QualificationReview(ref,State.REVOKED,prior==null ? null : prior.replayId(),prior==null ? null : prior.replayHash(),operator,note,clock.instant());
            saveReview(ref,value); return value;
        });
    }
    private void saveReview(Reference ref,QualificationReview review) throws IOException {
        files.write("review-"+UUID.randomUUID()+".json",review); files.write("state-"+ref.key()+".json",review);
    }
    public List<RunbookEntry> runbooks() throws IOException {
        var result=new ArrayList<RunbookEntry>();
        for(Path path:list("runbook-",128)) { var book=read(path,RunbookVersion.class,16384); var review=reviewOf(book.reference());
            result.add(new RunbookEntry(book,review==null ? State.DRAFT : review.state(),review)); }
        return List.copyOf(result);
    }
    public Reference reference(String id,int version) throws IOException {
        ManagedApplication.identifier(id); var book=read(root.resolve("runbook-"+id+"-v"+version+".json"),RunbookVersion.class,16384);
        if(!book.id().equals(id) || book.version()!=version) throw new IOException("knowledge file identity mismatch"); return book.reference();
    }
    @Override public boolean available(ManagedApplication app) throws IOException {
        return runbooks().stream().anyMatch(e -> e.state()==State.REVIEWED && e.runbook().scope().matches(app))
            || cases().stream().anyMatch(e -> e.state()==State.REVIEWED && e.opsCase().scope().matches(app));
    }
    @Override public SearchResult search(ManagedApplication app,String query,int limit,List<DecisionEvidence> evidence) throws Exception {
        text(query,200); if(limit<1 || limit>3) throw new IllegalArgumentException("knowledge Top-K must be 1..3");
        var books=new ArrayList<RunbookVersion>();
        for(var entry:runbooks()) if(entry.state()==State.REVIEWED && entry.runbook().scope().matches(app)) {
            try { validate(app,List.of(entry.runbook().reference())); books.add(entry.runbook()); }
            catch(IllegalArgumentException e) { /* Revoked parent case is ineligible, never silently requalified. */ }
        }
        var cases=cases().stream().filter(c -> c.state()==State.REVIEWED && c.opsCase().scope().matches(app)).toList();
        var corpus=new ArrayList<String>(); books.forEach(b -> corpus.add(searchText(b))); cases.forEach(c -> corpus.add(caseText(c)));
        var scorer=new KeywordScorer(corpus); String normalized=query.toLowerCase(Locale.ROOT);
        var matched=books.stream().map(b -> new Match(b.reference(),b.title(),scorer.score(normalized,searchText(b)),
            "exact environment/service/applicationVersion; keyword BM25",applicable(b,app,evidence,clock),b))
            .filter(m -> m.score()>0).sorted(Comparator.comparingDouble(Match::score).reversed().thenComparing(m -> m.reference().key())).limit(limit).toList();
        var similar=cases.stream().map(c -> new CaseMatch(c.opsCase().id(),ManagedContracts.hash(c.opsCase()),c.review().confirmedCause(),
            c.opsCase().outcome(),c.opsCase().impact(),scorer.score(normalized,caseText(c))))
            .filter(c -> c.score()>0).sorted(Comparator.comparingDouble(CaseMatch::score).reversed().thenComparing(CaseMatch::id)).limit(limit).toList();
        return new SearchResult(matched,similar);
    }
    @Override public void validate(ManagedApplication app,List<Reference> refs) throws Exception {
        if(refs.size()>12 || refs.stream().distinct().count()!=refs.size()) throw new IllegalArgumentException("bounded distinct knowledge references required");
        for(var ref:refs) {
            if(ref.id().startsWith("case-")) {
                var value=read(root.resolve("case-"+ref.id()+".json"),OpsCase.class,65536);
                if(ref.version()!=1 || !ref.contentHash().equals(ManagedContracts.hash(value))) throw new IllegalArgumentException("case reference hash/version changed");
                requireReviewedCase(ref.id(),Scope.of(app)); continue;
            }
            var book=requireBody(ref); var review=reviewOf(ref);
            if(!book.scope().matches(app) || review==null || review.state()!=State.REVIEWED || !review.knowledge().equals(ref))
                throw new IllegalArgumentException("knowledge scope/version is not currently reviewed");
            if(review.at().isAfter(clock.instant())) throw new IllegalArgumentException("knowledge review is future dated");
            var replay=read(root.resolve(review.replayId()+".json"),ReplayReport.class,16384);
            requireReplay(book,replay);
            if(!replay.qualified() || !replay.knowledge().equals(ref) || !ManagedContracts.hash(replay).equals(review.replayHash()))
                throw new IllegalArgumentException("knowledge replay qualification changed");
            for(String id:book.sourceCaseIds()) requireReviewedCase(id,book.scope());
        }
    }
    @Override public AutoCloseable guard(ManagedApplication app,List<Reference> refs) throws Exception {
        if(refs.isEmpty()) return () -> {};
        FileChannel channel=FileChannel.open(root.resolve("execution.lock"),CREATE,WRITE);
        try { FileLock lock=channel.tryLock(); if(lock==null) throw new IOException("knowledge update is in progress");
            try { validate(app,refs); } catch(Exception e) { lock.close(); throw e; }
            return () -> { try { lock.close(); } finally { channel.close(); } };
        } catch(Exception e) { channel.close(); throw e; }
    }
    @Override public List<ManagedObserver.Probe> proposalProbes(ManagedApplication app,List<Reference> refs) throws Exception {
        validate(app,refs); var probes=new LinkedHashSet<ManagedObserver.Probe>();
        for(var ref:refs) if(!ref.id().startsWith("case-")) {
            var c=requireBody(ref).conditions(); probes.addAll(c.requiredProbes()); c.statuses().forEach(s -> probes.add(s.probe()));
            if(c.oomKilled()!=null) probes.add(ManagedObserver.Probe.RESOURCES);
        }
        return List.copyOf(probes);
    }
    @Override public void validateProposal(ManagedApplication app,List<Reference> refs,OpsDecision decision,List<DecisionEvidence> evidence) throws Exception {
        validate(app,refs); if(decision.disposition()!=OpsDecision.Disposition.PROPOSE_ACTION) return;
        var books=new ArrayList<RunbookVersion>(); for(var ref:refs) if(!ref.id().startsWith("case-")) books.add(requireBody(ref));
        if(!books.isEmpty() && books.stream().noneMatch(b -> b.playbook()==decision.playbook() && applicable(b,app,evidence,clock)))
            throw new IllegalArgumentException("retrieved runbooks do not permit this proposal under current evidence conditions");
    }
    public OpsCase archive(ManagedApplication app,ManagedIncident incident,ManagedIncidentController.DecisionRecord decision,
            String decisionArtifactHash,String outcomeArtifactHash) throws Exception {
        if(!incident.applicationId().equals(app.id()) || !incident.applicationHash().equals(ManagedContracts.hash(app))
                || !Set.of(ManagedIncident.State.RECOVERED,ManagedIncident.State.HANDOFF).contains(incident.state()))
            throw new IllegalArgumentException("postmortem needs registered independent recovery or human handoff");
        String id="case-"+incident.id().replaceFirst("^inc-","");
        var source=new CaseSource(app,incident,decision,decisionArtifactHash,outcomeArtifactHash);
        if(ManagedContracts.JSON.writeValueAsBytes(source).length>262144) throw new IOException("postmortem input exceeds byte cap");
        var value=new OpsCase(id,Scope.of(app),incident.id(),clock.instant(),incident.state()==ManagedIncident.State.RECOVERED
            ? OutcomeKind.INDEPENDENT_RECOVERY : OutcomeKind.HUMAN_HANDOFF,incident.detail(),decision==null ? null : decision.diagnosis(),
            (decision==null ? incident.evidence() : decision.evidence()).stream().map(e -> new EvidenceReference(e.id(),
                e.envelope()==null ? ManagedContracts.hash(e.observation()) : e.envelope().contentHash())).toList(),decisionArtifactHash,outcomeArtifactHash,ManagedContracts.hash(source));
        return files.locked(() -> { Path path=root.resolve("case-"+id+".json");
            if(Files.exists(path)) return read(path,OpsCase.class,65536);
            if(cases().size()>=256) throw new IOException("case archive cap reached");
            files.write("case-source-"+id+".json",source); files.write(path.getFileName().toString(),value); return value;
        });
    }
    public List<CaseEntry> cases() throws IOException {
        var result=new ArrayList<CaseEntry>(); for(Path p:list("case-",256)) {
            var value=read(p,OpsCase.class,65536); Path state=root.resolve("case-state-"+value.id()+".json");
            requireCaseSource(value);
            var review=Files.exists(state) ? read(state,CaseReview.class,4096) : null;
            if(review!=null && (!review.caseId().equals(value.id()) || !review.contentHash().equals(ManagedContracts.hash(value)))) throw new IOException("case review identity mismatch");
            result.add(new CaseEntry(value,review==null ? State.DRAFT : review.state(),review)); }
        return List.copyOf(result);
    }
    public CaseReview reviewCase(String id,DiagnosticReport.Cause cause,boolean revoke,String operator,String note) throws Exception {
        ManagedApplication.identifier(id); return files.locked(() -> {
            var value=read(root.resolve("case-"+id+".json"),OpsCase.class,65536); Path state=root.resolve("case-state-"+id+".json");
            requireCaseSource(value);
            if(Files.exists(state)) {
                var prior=read(state,CaseReview.class,4096);
                if(prior.state()==State.REVOKED && !revoke) throw new IllegalArgumentException("revoked case cannot be reactivated");
                if(prior.state()==State.REVIEWED && !revoke && !Objects.equals(prior.confirmedCause(),cause))
                    throw new IllegalArgumentException("reviewed case cause is immutable; revoke the incorrect case before creating corrected knowledge");
            }
            var review=new CaseReview(id,ManagedContracts.hash(value),revoke ? State.REVOKED : State.REVIEWED,cause,operator,note,clock.instant());
            files.write("case-review-"+UUID.randomUUID()+".json",review); files.write(state.getFileName().toString(),review); return review;
        });
    }
    private void requireReviewedCase(String id,Scope scope) throws IOException {
        var value=read(root.resolve("case-"+id+".json"),OpsCase.class,65536); var review=read(root.resolve("case-state-"+id+".json"),CaseReview.class,4096);
        requireCaseSource(value);
        if(!value.scope().equals(scope) || !review.caseId().equals(id) || review.state()!=State.REVIEWED || !ManagedContracts.hash(value).equals(review.contentHash()))
            throw new IllegalArgumentException("source case is not reviewed for this scope");
    }
    private void requireCaseSource(OpsCase value) throws IOException {
        var source=read(root.resolve("case-source-"+value.id()+".json"),CaseSource.class,262144);
        if(!ManagedContracts.hash(source).equals(value.sourceHash()) || !source.incident().id().equals(value.incidentId())
                || !Scope.of(source.application()).equals(value.scope())) throw new IOException("case source hash/identity changed");
    }
    public static boolean applicable(RunbookVersion book,ManagedApplication app,List<DecisionEvidence> evidence,Clock clock) {
        if(!book.scope().matches(app)) return false;
        try {
            DecisionEvidenceLedger.validateCollected(app,OpsDecision.systemEscalation("check knowledge conditions"),evidence,clock);
            var current=evidence.stream().filter(e -> e.currentAt(clock.instant())).toList(); var c=book.conditions();
            if(c.forbidTruncated() && current.stream().anyMatch(e -> e.envelope()!=null && e.envelope().quality()==EvidenceEnvelope.Quality.TRUNCATED)) return false;
            var required=new HashSet<>(c.requiredProbes()); c.statuses().forEach(s -> required.add(s.probe())); if(c.oomKilled()!=null) required.add(ManagedObserver.Probe.RESOURCES);
            for(var probe:required) {
                var values=current.stream().filter(e -> e.observation().probe()==probe).toList();
                if(values.isEmpty() || values.stream().anyMatch(e -> e.envelope()==null || e.envelope().quality()!=EvidenceEnvelope.Quality.COMPLETE)) return false;
                var expected=c.statuses().stream().filter(s -> s.probe()==probe).findFirst();
                if(expected.isPresent() && values.stream().anyMatch(e -> e.observation().status()!=expected.get().status())) return false;
                if(probe==ManagedObserver.Probe.RESOURCES && c.oomKilled()!=null && values.stream().anyMatch(e -> e.observation().payload().resources()==null
                        || e.observation().payload().resources().oomKilled()!=c.oomKilled())) return false;
            }
            if(book.playbook()!=null) DecisionEvidenceLedger.validateCollected(app,new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"knowledge applicability replay",
                current.stream().map(DecisionEvidence::id).limit(12).toList(),book.playbook(),List.of(),null),evidence,clock);
            return true;
        } catch(IllegalArgumentException e) { return false; }
    }
    private RunbookVersion requireBody(Reference ref) throws IOException {
        var value=read(body(ref),RunbookVersion.class,16384); if(!value.reference().equals(ref)) throw new IllegalArgumentException("knowledge body hash/version changed"); return value;
    }
    private void requireReplay(RunbookVersion book,ReplayReport replay) throws IOException {
        var samples=read(root.resolve("samples-"+replay.id()+".json"),ReplayInput.class,262144);
        if(!ManagedContracts.hash(samples).equals(replay.sampleHash()) || !samples.sampleVersion().equals(replay.sampleVersion())) throw new IllegalArgumentException("replay input changed");
        var expected=samples.samples().stream().map(s -> { boolean matches=applicable(book,s.application(),s.evidence(),Clock.fixed(s.at(),ZoneOffset.UTC));
            return new ReplayResult(s.id(),s.expectedApplicable(),matches,matches==s.expectedApplicable()); }).toList();
        if(!expected.equals(replay.results()) || replay.at().isAfter(clock.instant())) throw new IllegalArgumentException("replay report differs from frozen input");
    }
    private Path body(Reference ref) { return root.resolve("runbook-"+ref.key()+".json"); }
    private QualificationReview reviewOf(Reference ref) throws IOException {
        Path path=root.resolve("state-"+ref.key()+".json"); return Files.exists(path) ? read(path,QualificationReview.class,4096) : null;
    }
    private List<Path> list(String prefix,int limit) throws IOException {
        try(var entries=Files.list(root)) { var paths=entries.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".json"))
            .filter(p -> !p.getFileName().toString().startsWith("case-state-") && !p.getFileName().toString().startsWith("case-review-") && !p.getFileName().toString().startsWith("case-source-"))
            .sorted().limit(limit+1L).toList(); if(paths.size()>limit) throw new IOException("knowledge catalog cap exceeded"); return paths; }
    }
    private static String searchText(RunbookVersion b) { return (b.title()+" "+b.symptoms()+" "+String.join(" ",b.applicability())).toLowerCase(Locale.ROOT); }
    private static String caseText(CaseEntry c) { return (c.opsCase().impact()+" "+c.review().confirmedCause().name()+" "
        +(c.opsCase().proposedDiagnosis()==null ? "" : c.opsCase().proposedDiagnosis().summary())).toLowerCase(Locale.ROOT); }
    public static String contentHash(Object value) { return ManagedContracts.hash(value); }
    public static <T> T read(Path path,Class<T> type,int cap) throws IOException {
        byte[] bytes; try(var input=Files.newInputStream(path)) { bytes=input.readNBytes(cap+1); }
        if(bytes.length>cap) throw new IOException("knowledge input exceeds byte cap"); return ManagedContracts.JSON.readValue(bytes,type);
    }
}
