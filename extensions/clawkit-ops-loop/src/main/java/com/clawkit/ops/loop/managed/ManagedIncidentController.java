package com.clawkit.ops.loop.managed;

import com.clawkit.ops.loop.automation.AutomationTaskScheduler;
import com.clawkit.ops.loop.automation.ScheduledHandle;
import com.clawkit.reliability.CancellationTree;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.CancelRegistration;
import com.clawkit.tools.control.TokenBudget;
import com.clawkit.tools.control.WorkBudget;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Continuous read observation plus a separate, explicitly authorized repair consumer. Existing observe-only coordinator stays read-only. */
public final class ManagedIncidentController implements AutoCloseable {
    @FunctionalInterface public interface Decider {
        OpsDecisionAgent.Outcome decide(ManagedApplication app,ManagedObserver observer,ExecutionControl control,OpsDecisionAgent.DecisionContext context);
    }
    public record Settings(int maxDecisions,Duration incidentDeadline,Duration reevaluationCooldown) {
        public Settings {
            if (maxDecisions<1 || maxDecisions>6) throw new IllegalArgumentException("bounded decision count required");
            ManagedApplication.requireDuration(incidentDeadline,Duration.ofSeconds(1),Duration.ofHours(1));
            ManagedApplication.requireDuration(reevaluationCooldown,Duration.ofSeconds(1),Duration.ofMinutes(5));
        }
        public static Settings defaults() { return new Settings(3,Duration.ofMinutes(10),Duration.ofSeconds(10)); }
    }
    private final String applicationId;
    private final Supplier<ManagedApplication> applications;
    private final Supplier<ActionPolicy> policies;
    private final IndependentManagedVerifier.ObserverFactory observers;
    private final Decider decider;
    private final ManagedFixAdapter fix;
    private final ManagedRepairExecutor repair;
    private final IndependentManagedVerifier recoveryVerifier;
    private final ManagedIncidentStore store;
    private final ManagedIncidentStore.Lease lease;
    private final AutomationTaskScheduler scheduler;
    private final Settings settings;
    private final Clock clock;
    private final Consumer<ManagedIncidentStore.Event> listener;
    private final boolean captureEvaluationTrace;
    private final ReentrantLock cycleLock=new ReentrantLock();
    private final AtomicReference<CancellationTree> activeControl=new AtomicReference<>();
    private volatile String lastFailure;
    private KnowledgeAccess knowledge=KnowledgeAccess.none();
    private ScheduledHandle handle;
    private volatile boolean closed;

    public ManagedIncidentController(Supplier<ManagedApplication> applications,Supplier<ActionPolicy> policies,
            IndependentManagedVerifier.ObserverFactory observers,Decider decider,ManagedFixAdapter fix,
            ManagedRepairExecutor repair,IndependentManagedVerifier recoveryVerifier,ManagedIncidentStore store,
            AutomationTaskScheduler scheduler,Settings settings,Clock clock,Consumer<ManagedIncidentStore.Event> listener) throws Exception {
        this(applications,policies,observers,decider,fix,repair,recoveryVerifier,store,scheduler,settings,clock,listener,false);
    }
    /** Raw normalized request/response traces are opt-in for isolated evaluations, never the ordinary product default. */
    public ManagedIncidentController(Supplier<ManagedApplication> applications,Supplier<ActionPolicy> policies,
            IndependentManagedVerifier.ObserverFactory observers,Decider decider,ManagedFixAdapter fix,
            ManagedRepairExecutor repair,IndependentManagedVerifier recoveryVerifier,ManagedIncidentStore store,
            AutomationTaskScheduler scheduler,Settings settings,Clock clock,Consumer<ManagedIncidentStore.Event> listener,
            boolean captureEvaluationTrace) throws Exception {
        this.applications=Objects.requireNonNull(applications); this.policies=Objects.requireNonNull(policies);
        this.applicationId=applications.get().id(); this.observers=Objects.requireNonNull(observers);
        this.decider=Objects.requireNonNull(decider); this.fix=Objects.requireNonNull(fix); this.repair=Objects.requireNonNull(repair);
        this.recoveryVerifier=Objects.requireNonNull(recoveryVerifier); this.store=Objects.requireNonNull(store);
        this.scheduler=Objects.requireNonNull(scheduler); this.settings=Objects.requireNonNull(settings);
        this.clock=Objects.requireNonNull(clock); this.listener=Objects.requireNonNull(listener);
        this.captureEvaluationTrace=captureEvaluationTrace;
        this.lease=store.claim();
        try {
            project(); repair.recover(applications.get());
            var current=store.read().current();
            if (current!=null && (current.state()==ManagedIncident.State.EXECUTING || current.state()==ManagedIncident.State.INVESTIGATING))
                save(transition(current,ManagedIncident.State.HANDOFF,"controller restarted during decision/execution; inspect journal, never redispatch"),"RECOVERY_HANDOFF");
        } catch (Exception e) { lease.close(); throw e; }
    }

    public synchronized void start() throws Exception {
        if (closed) throw new IllegalStateException("controller is closed");
        store.requestMode(ManagedIncidentStore.Mode.RUNNING,clock.instant());
        if (handle==null || handle.isCancelled()) handle=scheduler.schedule(this::tickSafely,0,
            applications.get().checkInterval().toMillis(),TimeUnit.MILLISECONDS);
    }
    /** User-owned wiring, installed before the controller starts; model tools cannot change it. */
    public synchronized void configureKnowledge(KnowledgeAccess access) {
        if(handle!=null) throw new IllegalStateException("configure knowledge before starting control");
        knowledge=Objects.requireNonNull(access);
    }
    public void pause() throws Exception { requestMode(ManagedIncidentStore.Mode.PAUSED); }
    public void resume() throws Exception { start(); }
    public void stop() throws Exception { requestMode(ManagedIncidentStore.Mode.STOPPED); }
    /** Apply persisted external intent without overwriting a newer UI command. */
    public synchronized ManagedIncidentStore.Mode synchronizeRequestedMode() throws Exception {
        var mode=store.requestedMode();
        if (mode!=ManagedIncidentStore.Mode.RUNNING) {
            var control=activeControl.get(); if (control!=null) control.cancel();
        } else if (!closed && (handle==null || handle.isCancelled())) {
            handle=scheduler.schedule(this::tickSafely,0,applications.get().checkInterval().toMillis(),TimeUnit.MILLISECONDS);
        }
        return mode;
    }
    private void requestMode(ManagedIncidentStore.Mode mode) throws Exception {
        store.requestMode(mode,clock.instant());
        var control=activeControl.get(); if (control!=null) control.cancel();
    }
    public ManagedIncidentStore.Snapshot status() throws Exception { return store.read(); }
    public String lastFailure() { return lastFailure; }
    private void tickSafely() {
        try { tick(); }
        catch (Exception e) {
            lastFailure="controller halted: "+e.getClass().getSimpleName();
            try { store.requestMode(ManagedIncidentStore.Mode.PAUSED,clock.instant()); } catch (Exception ignored) {}
        }
    }

    /** Same cycle as the scheduled path. Exposed for deterministic integration tests and CLI run-once. */
    public void tick() throws Exception {
        if (closed || !cycleLock.tryLock()) return;
        var cancellation=CancellationTree.unbounded(); activeControl.set(cancellation);
        ExecutionControl control=modeControl(cancellation);
        try {
            project();
            if (store.requestedMode()!=ManagedIncidentStore.Mode.RUNNING) return;
            ManagedApplication app=applications.get();
            if (!applicationId.equals(app.id())) throw new IllegalStateException("controller application identity changed");
            var current=store.read().current();
            if (current!=null && !current.terminal() && !current.applicationHash().equals(ManagedContracts.hash(app))) {
                save(transition(current,ManagedIncident.State.HANDOFF,"application configuration changed; re-register and review incident"),"CONFIGURATION_HANDOFF");
                store.requestMode(ManagedIncidentStore.Mode.PAUSED,clock.instant());
                return;
            }
            // A maintenance/stopped intent suppresses incident discovery and model requests.
            if (app.desiredState()==ManagedApplication.DesiredState.STOPPED
                    || (app.maintenanceUntil()!=null && clock.instant().isBefore(app.maintenanceUntil()))) {
                if (current!=null && !current.terminal() && current.state()!=ManagedIncident.State.HANDOFF && current.state()!=ManagedIncident.State.CANCELLED)
                    save(transition(current,ManagedIncident.State.CANCELLED,"user desired stop/maintenance; no repair"),"INTENT_CANCELLED");
                return;
            }
            boolean investigationDue=current!=null && current.state()==ManagedIncident.State.INVESTIGATING && current.decision()!=null
                && current.nextDecisionAt()!=null && !clock.instant().isBefore(current.nextDecisionAt());
            List<DecisionEvidence> evidence=observe(app,investigationDue ? current.decision().nextProbes() : List.of());
            store.observations(evidence.stream().map(DecisionEvidence::observation).toList(),"OBSERVATION");
            boolean healthy=healthy(evidence,clock.instant());
            String symptomHash=ManagedContracts.snapshot(evidence);
            if (current==null || current.terminal()) {
                if (healthy) { store.heartbeat(clock.instant()); return; }
                current=new ManagedIncident("inc-"+UUID.randomUUID(),app.id(),ManagedContracts.hash(app),ManagedContracts.target(app),
                    ManagedIncident.State.OPEN,clock.instant(),clock.instant(),1,0,null,clock.instant().plus(settings.incidentDeadline()),
                    symptomHash,evidence,null,List.of(),null,null,"new abnormal service/health/business observation");
                save(current,"INCIDENT_CREATED");
            } else {
                current=observed(current,evidence,symptomHash);
                save(current,"OBSERVATION_MERGED");
            }
            // HANDOFF remains sticky even if later observations look healthy; unknown execution is not retroactively a success.
            if (current.state()==ManagedIncident.State.HANDOFF || (current.state()==ManagedIncident.State.CANCELLED && !healthy)) return;
            if (healthy) {
                var verification=recoveryVerifier.verify(app,observers);
                store.observations(verification);
                store.artifact("recovery-"+current.id()+"-"+current.observations()+".json",verification);
                if (verification.recovered()) save(transition(current,ManagedIncident.State.RECOVERED,"independent sustained recovery; no new repair"),"INCIDENT_RECOVERED");
                return;
            }
            if (!clock.instant().isBefore(current.deadline())) {
                save(transition(current,ManagedIncident.State.HANDOFF,"incident deadline exceeded; stop autonomous investigation"),"DECISION_LIMIT_HANDOFF"); return;
            }
            if (current.state()==ManagedIncident.State.AWAITING_APPROVAL) return;
            boolean first=current.decisions()==0;
            boolean changed=current.decision()!=null && !symptomHash.equals(ManagedContracts.snapshot(current.decisionEvidence()));
            boolean scheduled=(current.state()==ManagedIncident.State.WAITING || current.state()==ManagedIncident.State.INVESTIGATING)
                && current.nextDecisionAt()!=null && !clock.instant().isBefore(current.nextDecisionAt());
            int priorWait=current.decision()==null || current.decision().recheckAfterSeconds()==null ? 0 : current.decision().recheckAfterSeconds();
            long cooldownSeconds=settings.reevaluationCooldown().toSeconds();
            Instant cooldownAt=current.nextDecisionAt()==null ? null : current.nextDecisionAt()
                .minusSeconds(Math.max(priorWait,cooldownSeconds)).plusSeconds(cooldownSeconds);
            boolean cooldown=cooldownAt==null || !clock.instant().isBefore(cooldownAt);
            if (!first && !(cooldown && (changed || scheduled))) return;
            if (current.decisions()>=settings.maxDecisions()) {
                save(transition(current,ManagedIncident.State.HANDOFF,"decision count limit reached"),"DECISION_LIMIT_HANDOFF"); return;
            }
            if (!running(control)) return;
            String artifact="decision-"+current.id()+"-"+(current.decisions()+1)+".json";
            store.artifact(artifact,Map.of("status","STARTED","incidentId",current.id(),"at",clock.instant()));
            current=planned(current,artifact); save(current,"DECISION_STARTED");
            OpsDecisionAgent.Outcome outcome;
            try (ManagedObserver observer=observers.open()) { outcome=decider.decide(app,observer,control,
                new OpsDecisionAgent.DecisionContext(current.id(),current.decisions(),current.decision(),evidence)); }
            store.artifact(artifact,captureEvaluationTrace ? outcome : DecisionRecord.from(outcome));
            if (!running(control)) {
                save(transition(current,ManagedIncident.State.WAITING,"paused/stopped during investigation; no repair dispatched"),"DECISION_CANCELLED"); return;
            }
            if ((outcome.origin()!=OpsDecisionAgent.Origin.MODEL && outcome.origin()!=OpsDecisionAgent.Origin.RULES) || outcome.failureType()!=null) {
                save(transition(current,ManagedIncident.State.HANDOFF,"model decision unavailable; system handoff"),"DECISION_FAILED"); return;
            }
            DecisionEvidenceLedger.validateCollected(app,outcome.decision(),outcome.evidence(),clock);
            var decision=outcome.decision();
            ManagedIncident.State next=switch(decision.disposition()) {
                case INVESTIGATE -> ManagedIncident.State.INVESTIGATING;
                case WAIT -> ManagedIncident.State.WAITING;
                case ESCALATE -> ManagedIncident.State.HANDOFF;
                case PROPOSE_ACTION -> ManagedIncident.State.AWAITING_APPROVAL;
            };
            int requested=decision.recheckAfterSeconds()==null ? 0 : decision.recheckAfterSeconds();
            Instant recheck=clock.instant().plusSeconds(Math.max(requested,settings.reevaluationCooldown().toSeconds()));
            current=decided(current,next,decision,outcome.evidence(),recheck,decision.reason());
            save(current,"DECISION_SUBMITTED");
            if (decision.disposition()!=OpsDecision.Disposition.PROPOSE_ACTION) return;
            ActionPolicy policy=policies.get();
            if (policy.mode()==ActionPolicy.Mode.OBSERVE) {
                save(transition(current,ManagedIncident.State.HANDOFF,"observation-only permission; repair proposal retained"),"OBSERVE_HANDOFF"); return;
            }
            if (policy.permitsAuto(app,decision.playbook(),clock.instant()) && repair.controlStore().degradation(app.id()).isEmpty())
                execute(current,app,RepairAuthorization.Automatic.INSTANCE,control);
        } finally { activeControl.compareAndSet(cancellation,null); cycleLock.unlock(); }
    }
    public record ProviderUsage(Instant startedAt,Instant completedAt,com.clawkit.provider.TokenUsage usage,String failureType) {}
    public record DecisionRecord(OpsDecisionAgent.Origin origin,OpsDecision decision,List<DecisionEvidence> evidence,
            List<String> rejectedSubmissions,List<ProviderUsage> providerUsage,String failureType,DiagnosticReport diagnosis,List<OpsKnowledge.Reference> knowledgeReferences,List<String> triggerReferences) {
        public DecisionRecord { knowledgeReferences=knowledgeReferences==null ? List.of() : List.copyOf(knowledgeReferences); triggerReferences=triggerReferences==null ? List.of() : List.copyOf(triggerReferences); }
        static DecisionRecord from(OpsDecisionAgent.Outcome outcome) {
            return new DecisionRecord(outcome.origin(),outcome.decision(),outcome.evidence(),outcome.rejectedSubmissions(),
                outcome.providerExchanges().stream().map(e -> new ProviderUsage(e.startedAt(),e.completedAt(),
                    e.usage(),e.failureType())).toList(),outcome.failureType(),outcome.diagnosis(),outcome.knowledgeReferences(),outcome.triggerReferences());
        }
    }

    /** Explicit human approval refreshes the proposal and evidence first. Rejection/cancellation never calls repair. */
    public void approve(String incidentId,String operator) throws Exception {
        approve(incidentId,operator,null,null,null);
    }
    public void apply(ManagedCommandInbox.Command command) throws Exception {
        if (clock.instant().isBefore(command.issuedAt()) || !clock.instant().isBefore(command.expiresAt()))
            throw new IllegalArgumentException("human command expired or future dated");
        if (command.type()==ManagedCommandInbox.Type.APPROVE)
            approve(command.incidentId(),command.operator(),command.applicationHash(),command.playbook(),command.expiresAt());
        else {
            cycleLock.lock();
            try { requireCommandMatches(requireAwaiting(command.incidentId()),command.applicationHash(),command.playbook()); reject(command.incidentId()); }
            finally { cycleLock.unlock(); }
        }
    }
    private static void requireCommandMatches(ManagedIncident incident,String applicationHash,OpsDecision.Playbook playbook) {
        if (applicationHash!=null && (!incident.applicationHash().equals(applicationHash) || incident.decision().playbook()!=playbook))
            throw new IllegalArgumentException("approved application/action differs from the current proposal");
    }
    private void approve(String incidentId,String operator,String expectedApplicationHash,OpsDecision.Playbook expectedPlaybook,Instant consentExpiry) throws Exception {
        cycleLock.lock(); var cancellation=CancellationTree.unbounded(); activeControl.set(cancellation);
        ExecutionControl control=modeControl(cancellation);
        try {
            project(); if (!running(control)) throw new IllegalStateException("controller is paused/stopped");
            var incident=requireAwaiting(incidentId); ManagedApplication app=applications.get();
            requireCommandMatches(incident,expectedApplicationHash,expectedPlaybook);
            if (!incident.applicationHash().equals(ManagedContracts.hash(app))) throw new IllegalStateException("application configuration changed");
            List<DecisionEvidence> fresh=observe(app);
            var refreshed=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,incident.decision().reason(),
                fresh.stream().map(DecisionEvidence::id).toList(),incident.decision().playbook(),List.of(),null);
            var authorization=RepairAuthorization.approved(incidentId,app,refreshed,fresh,operator,clock);
            if (consentExpiry!=null && consentExpiry.isBefore(authorization.binding().expiresAt())) {
                var binding=authorization.binding();
                authorization=new RepairAuthorization.Human(new RepairAuthorization.Binding(binding.incidentId(),binding.applicationHash(),binding.playbook(),
                    binding.snapshotHash(),binding.issuedAt(),consentExpiry),operator);
            }
            incident=decided(incident,incident.state(),refreshed,fresh,null,"explicit human approval after fresh observation");
            save(incident,"HUMAN_APPROVED"); execute(incident,app,authorization,control);
        } finally { activeControl.compareAndSet(cancellation,null); cycleLock.unlock(); }
    }
    public void reject(String incidentId) throws Exception {
        cycleLock.lock();
        try { project(); save(transition(requireAwaiting(incidentId),ManagedIncident.State.CANCELLED,"human rejected/cancelled repair"),"HUMAN_REJECTED"); }
        finally { cycleLock.unlock(); }
    }
    private ManagedIncident requireAwaiting(String incidentId) throws Exception {
        ManagedApplication.identifier(incidentId); var current=store.read().current();
        if (current==null || !current.id().equals(incidentId) || current.state()!=ManagedIncident.State.AWAITING_APPROVAL)
            throw new IllegalStateException("incident is not awaiting approval");
        return current;
    }
    private void execute(ManagedIncident incident,ManagedApplication app,RepairAuthorization.Request authorization,ExecutionControl control) throws Exception {
        var refs=new ArrayList<OpsKnowledge.Reference>();
        if(incident.decisionArtifact()!=null) {
            var record=ManagedKnowledgeStore.read(store.artifactPath(incident.decisionArtifact()),com.fasterxml.jackson.databind.JsonNode.class,262144);
            var values=record.path("knowledgeReferences");
            if(!values.isMissingNode() && !values.isNull()) {
                if(!values.isArray() || values.size()>12) throw new IllegalArgumentException("invalid knowledge references in decision artifact");
                for(var value:values) refs.add(ManagedContracts.JSON.treeToValue(value,OpsKnowledge.Reference.class));
            }
        }
        try(var guard=knowledge.guard(app,refs)) {
            var probes=knowledge.proposalProbes(app,refs);
            if(!probes.isEmpty()) {
                var fresh=observe(app,probes);
                knowledge.validateProposal(app,refs,incident.decision(),fresh);
            }
            executeQualified(incident,app,authorization,control);
        }
        catch(Exception e) {
            var current=store.read().current();
            if(current!=null && current.state()==ManagedIncident.State.AWAITING_APPROVAL)
                save(transition(current,ManagedIncident.State.HANDOFF,"knowledge qualification unavailable; no repair dispatched"),"KNOWLEDGE_HANDOFF");
            throw e;
        }
    }
    private void executeQualified(ManagedIncident incident,ManagedApplication app,RepairAuthorization.Request authorization,ExecutionControl control) throws Exception {
        if (!running(control)) return;
        incident=transition(incident,ManagedIncident.State.EXECUTING,"fresh precheck and authorized execution"); save(incident,"REPAIR_STARTED");
        var outcome=repair.execute(incident.id(),app,incident.decision(),incident.decisionEvidence(),authorization,
            applications,policies,observers,fix,control);
        String artifact="repair-"+incident.id()+".json"; store.artifact(artifact,outcome);
        store.observations(outcome.verification());
        ManagedIncident.State next=switch(outcome.status()) {
            case RECOVERED -> ManagedIncident.State.RECOVERED;
            case REQUIRES_APPROVAL -> ManagedIncident.State.AWAITING_APPROVAL;
            default -> ManagedIncident.State.HANDOFF;
        };
        String detail=outcome.detail();
        // A refused precheck can mean the application recovered while the model was thinking.
        // Only a known no-dispatch result may take this branch; unknown outcomes remain sticky.
        if (outcome.status()==ManagedRepairExecutor.Status.NO_EFFECT && running(control)
                && incident.applicationHash().equals(ManagedContracts.hash(applications.get()))) {
            var verification=recoveryVerifier.verify(app,observers);
            store.observations(verification);
            store.artifact("no-effect-recovery-"+incident.id()+".json",verification);
            if (verification.recovered()) {
                next=ManagedIncident.State.RECOVERED;
                detail="no repair dispatched; independent sustained self-recovery";
            }
        }
        var summary=new ManagedIncident.RepairSummary(outcome.status(),outcome.attemptId(),outcome.attemptState(),artifact);
        incident=new ManagedIncident(incident.id(),incident.applicationId(),incident.applicationHash(),incident.target(),next,
            incident.createdAt(),incident.lastObservedAt(),incident.observations(),incident.decisions(),incident.nextDecisionAt(),incident.deadline(),
            incident.symptomHash(),incident.evidence(),incident.decision(),incident.decisionEvidence(),incident.decisionArtifact(),summary,detail);
        save(incident,next==ManagedIncident.State.RECOVERED ? "INCIDENT_RECOVERED" : "REPAIR_HANDOFF");
    }
    private List<DecisionEvidence> observe(ManagedApplication app) throws Exception {
        return observe(app,List.of());
    }
    private List<DecisionEvidence> observe(ManagedApplication app,List<Probe> requested) throws Exception {
        var ledger=new DecisionEvidenceLedger(app,clock);
        try (ManagedObserver observer=observers.open()) {
            Set<Probe> probes=new LinkedHashSet<>(List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES));
            probes.addAll(requested);
            for (Probe probe:probes) {
                try { ledger.collect(observer,probe); }
                catch (InterruptedException e) { throw e; }
                catch (Exception e) {
                    ledger.collect((a,p) -> new Observation(a.targetId(),a.composeProject(),a.service(),p,clock.instant(),Status.UNKNOWN,
                        "probe unavailable: "+e.getClass().getSimpleName()),probe);
                }
            }
        }
        return ledger.snapshot();
    }
    private static boolean healthy(List<DecisionEvidence> evidence,Instant now) {
        var core=Set.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES);
        if (!evidence.stream().map(e -> e.observation().probe()).collect(java.util.stream.Collectors.toSet()).containsAll(core)) return false;
        return evidence.stream().filter(e -> core.contains(e.observation().probe())).allMatch(e -> e.currentAt(now) && (e.observation().probe()==Probe.SERVICE
            ? e.observation().status()==Status.RUNNING : e.observation().status()==Status.HEALTHY));
    }
    private boolean running(ExecutionControl control) {
        return !control.isCancelled();
    }
    private ExecutionControl modeControl(CancellationTree cancellation) {
        return new ExecutionControl() {
            @Override public boolean isCancelled() {
                try { if (store.requestedMode()!=ManagedIncidentStore.Mode.RUNNING) cancellation.cancel(); }
                catch (Exception e) { cancellation.cancel(); }
                return cancellation.isCancelled();
            }
            @Override public Optional<Instant> deadline() { return cancellation.deadline(); }
            @Override public TokenBudget tokenBudget() { return cancellation.tokenBudget(); }
            @Override public WorkBudget workBudget() { return cancellation.workBudget(); }
            @Override public CancelRegistration onCancel(Runnable action) { isCancelled(); return cancellation.onCancel(action); }
        };
    }
    private void save(ManagedIncident incident,String kind) throws Exception { store.save(incident,kind,clock.instant()); project(); }
    private void project() throws Exception { store.project(listener); }
    private ManagedIncident observed(ManagedIncident i,List<DecisionEvidence> evidence,String hash) {
        return new ManagedIncident(i.id(),i.applicationId(),i.applicationHash(),i.target(),i.state(),i.createdAt(),clock.instant(),i.observations()+1,
            i.decisions(),i.nextDecisionAt(),i.deadline(),hash,evidence,i.decision(),i.decisionEvidence(),i.decisionArtifact(),i.repair(),i.detail());
    }
    private static ManagedIncident transition(ManagedIncident i,ManagedIncident.State state,String detail) {
        return new ManagedIncident(i.id(),i.applicationId(),i.applicationHash(),i.target(),state,i.createdAt(),i.lastObservedAt(),i.observations(),
            i.decisions(),i.nextDecisionAt(),i.deadline(),i.symptomHash(),i.evidence(),i.decision(),i.decisionEvidence(),i.decisionArtifact(),i.repair(),detail);
    }
    private static ManagedIncident planned(ManagedIncident i,String artifact) {
        return new ManagedIncident(i.id(),i.applicationId(),i.applicationHash(),i.target(),ManagedIncident.State.INVESTIGATING,i.createdAt(),i.lastObservedAt(),
            i.observations(),i.decisions()+1,i.nextDecisionAt(),i.deadline(),i.symptomHash(),i.evidence(),i.decision(),i.decisionEvidence(),artifact,i.repair(),"bounded decision started");
    }
    private static ManagedIncident decided(ManagedIncident i,ManagedIncident.State state,OpsDecision decision,List<DecisionEvidence> evidence,Instant recheck,String detail) {
        return new ManagedIncident(i.id(),i.applicationId(),i.applicationHash(),i.target(),state,i.createdAt(),i.lastObservedAt(),i.observations(),
            i.decisions(),recheck,i.deadline(),i.symptomHash(),i.evidence(),decision,evidence,i.decisionArtifact(),i.repair(),detail);
    }
    @Override public synchronized void close() throws Exception {
        if (closed) return;
        stop(); closed=true;
        synchronized(this) { if (handle!=null) handle.cancel(false); scheduler.close(); }
        // Keep the process lease until an in-flight action has finished recording its real outcome.
        cycleLock.lock(); try { lease.close(); } finally { cycleLock.unlock(); }
    }
}
