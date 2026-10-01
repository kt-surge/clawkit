package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** One local Alertmanager inbox. Atomic snapshot preserves identity, duplicates, resolutions and reversible links. */
public final class ManagedTriggerStore {
    public record DependencyPin(String service,String containerId) {
        public DependencyPin { ManagedApplication.identifier(service); if(containerId==null || !containerId.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("full dependency container id required"); }
    }
    public record Binding(String applicationId,long applicationVersion,String applicationHash,String targetHash,String environment,String service,
            String daemonId,String containerId,List<DependencyPin> dependencies) {
        public Binding { ManagedApplication.identifier(applicationId); ManagedApplication.identifier(environment); ManagedApplication.identifier(service);
            OpsKnowledge.hash(applicationHash); OpsKnowledge.hash(targetHash); OpsKnowledge.text(daemonId,200); dependencies=List.copyOf(dependencies);
            if(applicationVersion<1 || containerId==null || !containerId.matches("[a-f0-9]{64}") || dependencies.size()>16) throw new IllegalArgumentException("bounded pinned binding required"); }
        public static Binding from(ManagedRegistrationStore.Registration reg) {
            var a=reg.application(); var t=reg.target(); return new Binding(a.id(),a.version(),ManagedContracts.hash(a),ManagedContracts.hash(t),a.composeProject(),a.service(),t.daemonId(),t.containerId(),
                t.dependencies().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> new DependencyPin(e.getKey(),e.getValue())).toList());
        }
        public boolean matches(ManagedApplication app) { return applicationId.equals(app.id()) && applicationVersion==app.version() && applicationHash.equals(ManagedContracts.hash(app)); }
    }
    public record SourceConfig(String sourceId,long version,boolean enabled,String tokenHash,List<Binding> bindings,Instant at) {
        public SourceConfig { ManagedApplication.identifier(sourceId); OpsKnowledge.hash(tokenHash); bindings=List.copyOf(bindings); Objects.requireNonNull(at);
            if(version<1 || bindings.size()>64 || bindings.stream().map(Binding::applicationId).distinct().count()!=bindings.size()) throw new IllegalArgumentException("bounded unique source bindings required"); }
    }
    public enum State { QUEUED, PENDING_REVIEW, ATTACHED, NO_INCIDENT, OBSOLETE, SOURCE_RESOLVED }
    public record Entry(TriggerEnvelope trigger,long deliveries,Instant firstReceivedAt,Instant lastReceivedAt,State state,String incidentId,String detail) {
        public Entry { Objects.requireNonNull(trigger); Objects.requireNonNull(firstReceivedAt); Objects.requireNonNull(lastReceivedAt); Objects.requireNonNull(state);
            if(deliveries<1 || deliveries>1000000 || lastReceivedAt.isBefore(firstReceivedAt)) throw new IllegalArgumentException("invalid delivery counters");
            OpsKnowledge.text(detail,500); if(incidentId!=null) ManagedApplication.identifier(incidentId); }
    }
    public record Snapshot(List<Entry> entries,List<IncidentRelation> relations) {
        public Snapshot { entries=List.copyOf(entries); relations=List.copyOf(relations);
            if(entries.size()>256 || relations.size()>256 || entries.stream().map(e -> e.trigger().eventId()).distinct().count()!=entries.size()
                    || relations.stream().map(IncidentRelation::id).distinct().count()!=relations.size()) throw new IllegalArgumentException("bounded unique trigger snapshot required");
            var ids=entries.stream().map(e -> e.trigger().eventId()).collect(java.util.stream.Collectors.toSet());
            if(relations.stream().anyMatch(r -> !ids.contains(r.leftEventId()) || !ids.contains(r.rightEventId()))) throw new IllegalArgumentException("relation must reference preserved source events"); }
    }
    public record IngestResult(int added,int duplicates,int pendingReview) {}
    public record Rejection(String id,Instant at,String category,String payloadHash) {}
    private final Path root; private final ManagedControlStore files; private final Clock clock;
    public ManagedTriggerStore(Path root,Clock clock) throws IOException { this.root=root.toAbsolutePath().normalize(); this.clock=clock; files=new ManagedControlStore(this.root); }
    public SourceConfig config() throws IOException { Path p=root.resolve("source.json"); return Files.exists(p) ? ManagedKnowledgeStore.read(p,SourceConfig.class,65536) : null; }
    public SourceConfig bind(ManagedRegistrationStore.Registration registration,String token) throws Exception {
        if(token==null || token.length()<32 || token.length()>256 || token.isBlank()) throw new IllegalArgumentException("CLAWKIT_ALERT_TOKEN must be 32..256 characters");
        return files.locked(() -> { var before=config(); var bindings=new ArrayList<>(before==null ? List.<Binding>of() : before.bindings()); var binding=Binding.from(registration);
            bindings.removeIf(b -> b.applicationId().equals(binding.applicationId())); bindings.add(binding);
            var value=new SourceConfig("alertmanager-local",before==null ? 1 : before.version()+1,true,tokenHash(token),bindings,clock.instant());
            files.write("source-version-"+value.version()+".json",value); files.write("source.json",value); return value;
        });
    }
    public void disable() throws Exception { files.locked(() -> { var before=config(); if(before==null) return null;
        var value=new SourceConfig(before.sourceId(),before.version()+1,false,before.tokenHash(),before.bindings(),clock.instant());
        files.write("source-version-"+value.version()+".json",value); files.write("source.json",value); return null; }); }
    public boolean authenticated(String token) throws IOException {
        var c=config(); return c!=null && c.enabled() && token!=null && token.length()>=32 && token.length()<=256
            && java.security.MessageDigest.isEqual(tokenHash(token).getBytes(java.nio.charset.StandardCharsets.US_ASCII),c.tokenHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    public Snapshot snapshot() throws IOException { Path p=root.resolve("alerts.snapshot.json"); return Files.exists(p) ? ManagedKnowledgeStore.read(p,Snapshot.class,2097152) : new Snapshot(List.of(),List.of()); }
    public IngestResult ingest(List<TriggerEnvelope> triggers) throws Exception {
        if(triggers.size()>32) throw new IllegalArgumentException("alert batch cap is 32");
        return files.locked(() -> { var source=config(); if(source==null || !source.enabled()) throw new IllegalArgumentException("alert source disabled");
            var before=snapshot(); var rows=new LinkedHashMap<String,Entry>(); before.entries().forEach(e -> rows.put(e.trigger().eventId(),e));
            int added=0,duplicates=0;
            for(var trigger:triggers) {
                if(!trigger.sourceId().equals(source.sourceId()) || trigger.sourceVersion()!=source.version()) throw new IllegalArgumentException("source version changed during intake");
                if(rows.values().stream().filter(e -> e.trigger().episodeId().equals(trigger.episodeId())).map(Entry::trigger).anyMatch(t ->
                    !Objects.equals(t.applicationId(),trigger.applicationId()) || !Objects.equals(t.targetHash(),trigger.targetHash())
                        || !t.environment().equals(trigger.environment()) || !t.service().equals(trigger.service())))
                    throw new IllegalArgumentException("source episode identity collision");
                var previous=rows.get(trigger.eventId());
                if(previous!=null) {
                    var old=previous.trigger();
                    if(!Objects.equals(old.applicationId(),trigger.applicationId()) || !Objects.equals(old.targetHash(),trigger.targetHash())
                            || !old.environment().equals(trigger.environment()) || !old.service().equals(trigger.service())) throw new IllegalArgumentException("source event identity collision");
                    boolean incomplete=previous.state()==State.QUEUED && trigger.quality()!=TriggerEnvelope.Quality.COMPLETE;
                    rows.put(trigger.eventId(),new Entry(old,Math.min(1000000,previous.deliveries()+1),previous.firstReceivedAt(),
                        max(previous.lastReceivedAt(),trigger.receivedAt()),incomplete ? State.PENDING_REVIEW : previous.state(),previous.incidentId(),
                        incomplete ? "latest duplicate delivery incomplete: "+trigger.quality()+"; manual review" : previous.detail())); duplicates++; continue;
                }
                State state=trigger.quality()!=TriggerEnvelope.Quality.COMPLETE ? State.PENDING_REVIEW
                    : trigger.phase()==TriggerEnvelope.Phase.RESOLVED ? State.SOURCE_RESOLVED : State.QUEUED;
                rows.put(trigger.eventId(),new Entry(trigger,1,trigger.receivedAt(),trigger.receivedAt(),state,null,
                    state==State.PENDING_REVIEW ? "source/target/time incomplete; manual review" : "source claim retained; requires fresh independent observation")); added++;
            }
            var resolved=new HashSet<String>(); rows.values().stream().filter(e -> e.state()==State.SOURCE_RESOLVED).forEach(e -> resolved.add(e.trigger().episodeId()));
            rows.replaceAll((id,e) -> resolved.contains(e.trigger().episodeId()) && e.state()==State.QUEUED
                ? new Entry(e.trigger(),e.deliveries(),e.firstReceivedAt(),e.lastReceivedAt(),State.OBSOLETE,e.incidentId(),"resolution arrived before consumption; late firing cannot reopen this episode") : e);
            var relations=before.relations().stream().map(r -> resolved.contains(rows.get(r.leftEventId()).trigger().episodeId()) || resolved.contains(rows.get(r.rightEventId()).trigger().episodeId())
                ? revoke(r,"source episode resolved; incident recovery still independently verified") : r).toList();
            var after=new Snapshot(new ArrayList<>(rows.values()),relations); save(after);
            return new IngestResult(added,duplicates,(int)after.entries().stream().filter(e -> e.state()==State.PENDING_REVIEW).count());
        });
    }
    public List<Entry> queued(String appId) throws IOException {
        return snapshot().entries().stream().filter(e -> e.state()==State.QUEUED && appId.equals(e.trigger().applicationId()))
            .sorted(Comparator.comparing(Entry::firstReceivedAt)).limit(32).toList();
    }
    public boolean current(TriggerEnvelope trigger,ManagedRegistrationStore.Registration registration) throws IOException {
        var c=config(); return c!=null && c.enabled() && c.version()==trigger.sourceVersion() && c.bindings().stream()
            .anyMatch(b -> b.matches(registration.application()) && b.targetHash().equals(trigger.targetHash()) && b.equals(Binding.from(registration)));
    }
    public void complete(String eventId,String incidentId,boolean drift) throws Exception {
        files.locked(() -> { var before=snapshot(); var rows=new ArrayList<Entry>();
            for(var entry:before.entries()) { if(entry.trigger().eventId().equals(eventId) && entry.state()==State.QUEUED)
                entry=new Entry(entry.trigger(),entry.deliveries(),entry.firstReceivedAt(),entry.lastReceivedAt(),drift ? State.PENDING_REVIEW
                    : incidentId==null ? State.NO_INCIDENT : State.ATTACHED,incidentId,drift ? "source/registered target version drift; manual review" : incidentId==null
                        ? "fresh observation found no active incident; alert claim alone cannot create a repair" : "attached to existing independently observed incident"); rows.add(entry); }
            save(correlate(new Snapshot(rows,before.relations()))); return null;
        });
    }
    public void revokeRelation(String relationId,String note) throws Exception {
        OpsKnowledge.hash(relationId); OpsKnowledge.text(note,250); files.locked(() -> { var before=snapshot();
            if(before.relations().stream().noneMatch(r -> r.id().equals(relationId))) throw new IllegalArgumentException("unknown relation");
            save(new Snapshot(before.entries(),before.relations().stream().map(r -> r.id().equals(relationId) ? revoke(r,note) : r).toList())); return null; });
    }
    public void reject(String category,String payloadHash) throws Exception {
        OpsKnowledge.text(category,100); OpsKnowledge.hash(payloadHash); files.locked(() -> {
            try(var entries=Files.list(root)) { if(entries.filter(p -> p.getFileName().toString().startsWith("rejection-")).limit(129).count()>=128) throw new IOException("intake rejection cap reached"); }
            String id="rejection-"+UUID.randomUUID(); files.write(id+".json",new Rejection(id,clock.instant(),category,payloadHash)); return null; });
    }
    private Snapshot correlate(Snapshot snapshot) throws IOException {
        var c=config(); var relations=new LinkedHashMap<String,IncidentRelation>(); snapshot.relations().forEach(r -> relations.put(r.id(),r));
        if(c==null || !c.enabled()) return snapshot;
        var attached=snapshot.entries().stream().filter(e -> e.state()==State.ATTACHED).toList();
        var resolved=snapshot.entries().stream().filter(e -> e.state()==State.SOURCE_RESOLVED).map(e -> e.trigger().episodeId()).collect(java.util.stream.Collectors.toSet());
        for(int i=0;i<attached.size();i++) for(int j=i+1;j<attached.size();j++) {
            var left=attached.get(i); var right=attached.get(j); var l=left.trigger(); var r=right.trigger();
            if(l.applicationId().equals(r.applicationId()) || !l.environment().equals(r.environment()) || resolved.contains(l.episodeId()) || resolved.contains(r.episodeId())
                    || l.sourceVersion()!=c.version() || r.sourceVersion()!=c.version() || Duration.between(l.startsAt(),r.startsAt()).abs().compareTo(Duration.ofMinutes(2))>0) continue;
            var lb=c.bindings().stream().filter(b -> b.applicationId().equals(l.applicationId())).findFirst().orElse(null);
            var rb=c.bindings().stream().filter(b -> b.applicationId().equals(r.applicationId())).findFirst().orElse(null);
            if(lb==null || rb==null || !lb.daemonId().equals(rb.daemonId()) || !lb.targetHash().equals(l.targetHash()) || !rb.targetHash().equals(r.targetHash())) continue;
            boolean edge=lb.dependencies().stream().anyMatch(p -> p.service().equals(rb.service()) && p.containerId().equals(rb.containerId()))
                || rb.dependencies().stream().anyMatch(p -> p.service().equals(lb.service()) && p.containerId().equals(lb.containerId()));
            if(!edge) continue;
            String id=ManagedContracts.hash(List.of(l.eventId(),r.eventId()));
            relations.putIfAbsent(id,new IncidentRelation(id,l.eventId(),r.eventId(),left.incidentId(),right.incidentId(),ManagedContracts.hash(List.of(lb,rb)),
                min(l.startsAt(),r.startsAt()),max(l.startsAt(),r.startsAt()),"registered pinned dependency edge and startsAt within 120s; correlation, not root cause",IncidentRelation.State.ACTIVE,null));
        }
        return new Snapshot(snapshot.entries(),new ArrayList<>(relations.values()));
    }
    private void save(Snapshot value) throws IOException { if(ManagedContracts.JSON.writeValueAsBytes(value).length>2097152) throw new IOException("trigger snapshot byte cap reached"); files.write("alerts.snapshot.json",value); }
    private static IncidentRelation revoke(IncidentRelation r,String note) { return r.state()==IncidentRelation.State.REVOKED ? r : new IncidentRelation(r.id(),r.leftEventId(),r.rightEventId(),r.leftIncidentId(),r.rightIncidentId(),r.topologyHash(),r.windowStart(),r.windowEnd(),r.reason(),IncidentRelation.State.REVOKED,note); }
    public static String tokenHash(String token) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static Instant max(Instant a,Instant b) { return a.isAfter(b) ? a : b; }
    private static Instant min(Instant a,Instant b) { return a.isBefore(b) ? a : b; }
}
