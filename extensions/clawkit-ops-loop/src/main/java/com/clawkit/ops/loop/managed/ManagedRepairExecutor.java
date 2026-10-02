package com.clawkit.ops.loop.managed;

import com.clawkit.engine.impl.InternalToolRouter;
import com.clawkit.engine.impl.ToolCallExecutor;
import com.clawkit.engine.impl.ToolExecutionContext;
import com.clawkit.observability.RunRecorder;
import com.clawkit.reliability.attempt.*;
import com.clawkit.reliability.gate.SideEffectGate;
import com.clawkit.tools.*;
import com.clawkit.tools.action.*;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.schema.ToolCall;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Shared human/policy execution path; model never receives this executor or its repair tool. */
public final class ManagedRepairExecutor implements AutoCloseable {
    private static final String TOOL="managed_registered_repair";
    private final ManagedControlStore controlStore;
    private final FileActionAttemptStore attempts;
    private final ActionAttemptCoordinator coordinator;
    private final Clock clock;
    private final RunRecorder recorder;
    private final IndependentManagedVerifier verifier;
    public enum Status { RECOVERED, BLOCKED, REQUIRES_APPROVAL, NO_EFFECT, OUTCOME_UNKNOWN, VERIFICATION_FAILED }
    public record Outcome(Status status,String attemptId,AttemptState attemptState,
        RepairAuthorization authorization,List<DecisionEvidence> precheck,
        IndependentManagedVerifier.Result verification,ToolExecutionResult toolResult,String detail) {}

    public ManagedRepairExecutor(Path stateDirectory,Clock clock,RunRecorder recorder,IndependentManagedVerifier verifier) throws Exception {
        this.controlStore=new ManagedControlStore(stateDirectory.resolve("control"));
        this.attempts=new FileActionAttemptStore(stateDirectory.resolve("attempts"));
        this.coordinator=new ActionAttemptCoordinator(attempts,new ActionAttemptCoordinator.AttemptPolicy(1,Duration.ZERO),null);
        this.clock=Objects.requireNonNull(clock); this.recorder=Objects.requireNonNull(recorder);
        this.verifier=Objects.requireNonNull(verifier);
    }
    public ManagedControlStore controlStore() { return controlStore; }
    public FileActionAttemptStore attempts() { return attempts; }

    /** Called before observation starts. No recovery branch dispatches an action. */
    public void recover(ManagedApplication app) throws Exception {
        controlStore.locked(() -> { recoverLocked(app); return null; });
    }
    private void recoverLocked(ManagedApplication app) throws Exception {
        controlStore.degradation(app.id()); // also validate persistent state even if journal is empty
        for (ActionAttempt attempt:attempts.byTarget(ManagedContracts.target(app))) {
            var ticket=coordinator.ticketFor(attempt.attemptId());
            switch (attempt.state()) {
                case CREATED,WAITING_APPROVAL,PRECHECKING,READY -> coordinator.cancelBeforeDispatch(ticket,"controller restart before dispatch");
                case DISPATCH_INTENT -> {
                    coordinator.reportOutcome(ticket,EffectCertainty.EFFECT_UNKNOWN,FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN,"controller restart after durable intent");
                    controlStore.degrade(app,attempt.attemptId(),"dispatch outcome unknown after restart",clock.instant());
                }
                case OUTCOME_UNKNOWN,RECONCILING,EXECUTION_REPORTED,VERIFICATION_PENDING,VERIFYING,COMPENSATION_PENDING,ESCALATED ->
                    controlStore.degrade(app,attempt.attemptId(),"incomplete/failed execution requires human handoff",clock.instant());
                default -> {}
            }
        }
    }

    public Outcome execute(String incidentId,ManagedApplication app,OpsDecision decision,List<DecisionEvidence> evidence,
            RepairAuthorization.Request request,Supplier<ManagedApplication> currentApplication,
            Supplier<ActionPolicy> currentPolicy,IndependentManagedVerifier.ObserverFactory freshObservers,
            ManagedFixAdapter fix,ExecutionControl control) {
        try {
            ManagedApplication.identifier(incidentId);
            DecisionEvidenceLedger.validateCollected(app,decision,evidence,clock);
            if (decision.disposition()!=OpsDecision.Disposition.PROPOSE_ACTION)
                return blocked("decision does not propose an action");
            return controlStore.locked(() -> executeLocked(incidentId,app,decision,request,currentApplication,currentPolicy,freshObservers,fix,control));
        } catch (Exception e) {
            // A failure to project/persist downgrade after dispatch must never masquerade as a no-dispatch refusal.
            if (decision.playbook()!=null) {
                try {
                    var prior=attempts.byLogicalAction(ManagedContracts.descriptor(incidentId,app,decision.playbook()).contentDerivedActionId());
                    for (ActionAttempt attempt:prior) {
                        if (!Set.of(AttemptState.CREATED,AttemptState.WAITING_APPROVAL,AttemptState.PRECHECKING,AttemptState.READY,
                                AttemptState.CANCELLED_NO_EFFECT,AttemptState.FAILED_NO_EFFECT,AttemptState.VERIFIED_SUCCESS).contains(attempt.state()))
                            return new Outcome(Status.OUTCOME_UNKNOWN,attempt.attemptId(),attempt.state(),null,List.of(),null,null,
                                "post-dispatch bookkeeping unavailable: "+e.getClass().getSimpleName());
                    }
                } catch (Exception ignored) {
                    return new Outcome(Status.OUTCOME_UNKNOWN,null,null,null,List.of(),null,null,"execution journal unavailable; human reconciliation required");
                }
            }
            return blocked("repair refused: "+e.getClass().getSimpleName());
        }
    }

    private Outcome executeLocked(String incidentId,ManagedApplication app,OpsDecision decision,RepairAuthorization.Request request,
            Supplier<ManagedApplication> currentApplication,Supplier<ActionPolicy> currentPolicy,
            IndependentManagedVerifier.ObserverFactory freshObservers,ManagedFixAdapter fix,ExecutionControl control) throws Exception {
        // Startup and failure journal facts independently reconstruct the persistent downgrade.
        recoverLocked(app);
        ActionPolicy policy=currentPolicy.get();
        if (!scopeAllowed(policy,app,decision.playbook()) || !ManagedContracts.hash(app).equals(ManagedContracts.hash(currentApplication.get())))
            return blocked("application/policy scope, intent or version changed");
        if (request==RepairAuthorization.Automatic.INSTANCE
                && (controlStore.degradation(app.id()).isPresent() || !policy.permitsAuto(app,decision.playbook(),clock.instant())))
            return new Outcome(Status.REQUIRES_APPROVAL,null,null,null,List.of(),null,null,"automatic permission unavailable; human approval required");
        if (!(request instanceof RepairAuthorization.Human) && request!=RepairAuthorization.Automatic.INSTANCE)
            return blocked("explicit authorization source required");
        var descriptor=ManagedContracts.descriptor(incidentId,app,decision.playbook());
        if (!attempts.byLogicalAction(descriptor.contentDerivedActionId()).isEmpty())
            return blocked("incident/action already has an attempt; never redispatch it");
        var authorization=new AtomicReference<RepairAuthorization>();
        var dispatch=new AtomicReference<ManagedFixAdapter.Dispatch>();
        var precheck=new AtomicReference<List<DecisionEvidence>>(List.of());
        var verification=new AtomicReference<IndependentManagedVerifier.Result>();
        SideEffectGate gate=new SideEffectGate(coordinator,attempt -> {
            try {
                var result=verifier.verify(app,freshObservers);
                verification.set(result);
                if (!result.recovered()) controlStore.degrade(app,attempt.attemptId(),"independent sustained verification failed",clock.instant());
                return new SideEffectGate.VerificationOutcome(result.recovered(),"independent samples="+result.samples().size()+" recovered="+result.recovered());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("verification interrupted",e);
            } catch (Exception e) { throw new IllegalStateException("verification unavailable",e); }
        },attempt -> {
            try {
                if (control.isCancelled()) return new SideEffectGate.PrecheckOutcome(false,"cancelled before fresh precheck");
                ActionPolicy freshPolicy=currentPolicy.get();
                if (!policy.policyHash().equals(freshPolicy.policyHash())
                        || !ManagedContracts.hash(app).equals(ManagedContracts.hash(currentApplication.get()))
                        || !scopeAllowed(freshPolicy,app,decision.playbook()))
                    return new SideEffectGate.PrecheckOutcome(false,"configuration or policy drift");
                var ledger=new DecisionEvidenceLedger(app,clock);
                try (ManagedObserver observer=freshObservers.open()) {
                    for (Probe probe:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) ledger.collect(observer,probe);
                }
                List<DecisionEvidence> fresh=ledger.snapshot(); precheck.set(fresh);
                var refs=fresh.stream().map(DecisionEvidence::id).toList();
                ledger.validate(new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,decision.reason(),refs,decision.playbook(),List.of(),null));
                RepairAuthorization granted;
                if (request instanceof RepairAuthorization.Human human) {
                    if (!human.binding().matches(incidentId,app,decision.playbook(),fresh,clock.instant()))
                        return new SideEffectGate.PrecheckOutcome(false,"human approval expired or snapshot changed");
                    granted=human;
                } else {
                    if (controlStore.degradation(app.id()).isPresent() || !freshPolicy.permitsAuto(app,decision.playbook(),clock.instant()))
                        return new SideEffectGate.PrecheckOutcome(false,"automatic policy no longer authorizes action");
                    Instant expiry=fresh.stream().map(DecisionEvidence::validUntil).min(Instant::compareTo).orElseThrow();
                    if (freshPolicy.expiresAt().isBefore(expiry)) expiry=freshPolicy.expiresAt();
                    granted=new RepairAuthorization.Policy(new RepairAuthorization.Binding(incidentId,ManagedContracts.hash(app),decision.playbook(),
                        ManagedContracts.snapshot(fresh),clock.instant(),expiry),freshPolicy.policyHash(),freshPolicy.version());
                }
                // Re-read after slow probes; revocation/drift/cancellation cannot sneak through the observation window.
                if (control.isCancelled() || !freshPolicy.policyHash().equals(currentPolicy.get().policyHash())
                        || !scopeAllowed(freshPolicy,app,decision.playbook())
                        || !ManagedContracts.hash(app).equals(ManagedContracts.hash(currentApplication.get())))
                    return new SideEffectGate.PrecheckOutcome(false,"cancelled/revoked during fresh precheck");
                controlStore.authorize(attempt.attemptId(),granted);
                authorization.set(granted);
                dispatch.set(new ManagedFixAdapter.Dispatch(incidentId,attempt.attemptId()));
                return new SideEffectGate.PrecheckOutcome(true,"fresh target/intent/evidence and explicit "+(granted instanceof RepairAuthorization.Human ? "human" : "policy")+" authorization persisted");
            } catch (Exception e) { return new SideEffectGate.PrecheckOutcome(false,"fresh precheck refused: "+e.getClass().getSimpleName()); }
        });
        var registry=new ToolRegistry();
        Tool tool=new Tool() {
            @Override public String name() { return TOOL; }
            @Override public String description() { return "Apply one reviewed action to the immutable registered service"; }
            @Override public String inputSchema() { return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"; }
            @Override public ToolMetadata metadata() {
                var base=ToolMetadata.from(this);
                return new ToolMetadata(name(),description(),base.inputSchema(),null,
                    new ToolBehavior(false,ToolRiskLevel.HIGH,false,false,false,true,Set.of()),
                    new ToolExecutionPolicy(Duration.ofSeconds(90),8000,ToolExecutionPolicy.OutputTruncation.HEAD,ToolExecutionPolicy.ToolConcurrency.SERIAL),
                    ToolMetadataProvenance.builtin(name()));
            }
            @Override public ActionDescriptor describeAction(ToolExecutionRequest req) { return descriptor; }
            @Override @Deprecated public Result<String> execute(String args) { return new Result.Err<>(new Result.ErrorInfo("TYPED_REQUEST_REQUIRED","use executor")); }
            @Override public ToolExecutionResult execute(ToolExecutionRequest req) {
                try {
                    if (authorization.get()==null) throw new IllegalStateException("no persisted authorization");
                    if (control.isCancelled() || !policy.policyHash().equals(currentPolicy.get().policyHash())
                            || !ManagedContracts.hash(app).equals(ManagedContracts.hash(currentApplication.get()))
                            || !clock.instant().isBefore(authorization.get().binding().expiresAt()))
                        throw new IllegalStateException("authorization changed after dispatch intent; no transport call made");
                    var report=fix.execute(app,decision.playbook(),Objects.requireNonNull(dispatch.get()));
                    if (report.certainty()==EffectCertainty.EFFECT_CONFIRMED)
                        return ToolExecutionResult.success(req.toolCallId(),name(),report.detail(),0,metadata())
                            .withReliability(EffectCertainty.EFFECT_CONFIRMED,null,null);
                    if(report.certainty()==EffectCertainty.NO_EFFECT_CONFIRMED || report.certainty()==EffectCertainty.NOT_DISPATCHED)
                        return ToolExecutionResult.error(req.toolCallId(),name(),"REPAIR_REFUSED_BEFORE_EXECUTION",report.detail(),0,metadata())
                            .withReliability(report.certainty(),report.certainty()==EffectCertainty.NOT_DISPATCHED ? FailureClass.PRECONDITION_FAILED
                                : FailureClass.SERVER_REJECTED_BEFORE_EXECUTION,null);
                    return ToolExecutionResult.error(req.toolCallId(),name(),"REPAIR_OUTCOME_UNKNOWN",report.detail(),0,metadata())
                        .withReliability(EffectCertainty.EFFECT_UNKNOWN,FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN,null);
                } catch (Exception e) {
                    return ToolExecutionResult.error(req.toolCallId(),name(),"REPAIR_OUTCOME_UNKNOWN","transport outcome unknown: "+e.getClass().getSimpleName(),0,metadata())
                        .withReliability(EffectCertainty.EFFECT_UNKNOWN,FailureClass.EXECUTION_ERROR_OUTCOME_UNKNOWN,null);
                }
            }
        };
        registry.register(tool);
        // Domain permission is granted only to this privately composed tool, never to a general AUTO registry.
        PermissionPolicy permission=(mode,meta,req,grants) -> TOOL.equals(meta.name()) && req.arguments().isObject() && req.arguments().isEmpty()
            ? PermissionPolicy.PermissionDecision.allow()
            : PermissionPolicy.PermissionDecision.deny("DOMAIN_SCOPE_DENIED","not the registered repair action");
        var context=new ToolExecutionContext("repair-"+UUID.randomUUID(),1,PermissionMode.ASK,permission,null,recorder,
            new InternalToolRouter(),new DefaultApprovalGrantCache(),control,RunToolScope.LOCAL_ONLY);
        ToolExecutionResult result=new ToolCallExecutor(registry,gate).executeBatch(List.of(
            new ToolCall("repair",TOOL,ManagedContracts.JSON.createObjectNode())),context).results().getFirst();
        ActionAttempt attempt=result.attemptId()==null ? null : attempts.byId(result.attemptId()).orElse(null);
        Status status=attempt!=null && attempt.state()==AttemptState.VERIFIED_SUCCESS ? Status.RECOVERED
            : result.effectCertainty()==EffectCertainty.EFFECT_UNKNOWN ? Status.OUTCOME_UNKNOWN
            : verification.get()!=null && !verification.get().recovered() ? Status.VERIFICATION_FAILED
            : attempt!=null && (attempt.state()==AttemptState.FAILED_NO_EFFECT || attempt.state()==AttemptState.CANCELLED_NO_EFFECT) ? Status.NO_EFFECT
            : Status.BLOCKED;
        if (attempt!=null && status!=Status.RECOVERED && status!=Status.NO_EFFECT) {
            controlStore.degrade(app,attempt.attemptId(),"repair not independently verified: "+status,clock.instant());
            if (attempt.state()==AttemptState.COMPENSATION_PENDING) {
                coordinator.escalate(coordinator.ticketFor(attempt.attemptId()),"verification failed; no automatic rollback");
                attempt=attempts.byId(attempt.attemptId()).orElseThrow();
            }
        }
        return new Outcome(status,attempt==null ? null : attempt.attemptId(),attempt==null ? null : attempt.state(),
            authorization.get(),precheck.get(),verification.get(),result,"repair outcome="+status);
    }
    private boolean scopeAllowed(ActionPolicy policy,ManagedApplication app,OpsDecision.Playbook playbook) {
        return policy!=null && policy.mode()!=ActionPolicy.Mode.OBSERVE && policy.applicationId().equals(app.id())
            && policy.applicationVersion()==app.version() && policy.playbooks().contains(playbook)
            && clock.instant().isBefore(policy.expiresAt()) && app.repairIntendedAt(clock.instant());
    }
    private static Outcome blocked(String detail) { return new Outcome(Status.BLOCKED,null,null,null,List.of(),null,null,detail); }
    @Override public void close() { attempts.close(); }
}
