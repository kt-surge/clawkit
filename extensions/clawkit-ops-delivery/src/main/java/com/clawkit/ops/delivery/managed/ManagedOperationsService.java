package com.clawkit.ops.delivery.managed;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.ops.loop.automation.JdkAutomationTaskScheduler;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.*;
import com.clawkit.provider.LLMProvider;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Product facade. Inspect/configure needs no model; diagnose investigates without granting or executing repairs. */
public final class ManagedOperationsService {
    public record RegistrationRequest(String id,Path composeFile,String context,String project,String service,List<String> dependencies,
            boolean stateless,URI healthUri,URI businessUri,String businessMarker,Duration checkInterval) {}
    public record StatusView(String applicationId,String target,String desiredState,String requestedMode,String permission,
            String qualification,Instant permissionExpiresAt,String incidentId,String state,String decision,String action,String reason,
            String executionOutcome,String degradationReason,Instant lastObservation,long observations,int decisions,Path evidenceDirectory,boolean processActive,
            Map<String,String> observedFacts) {
        public StatusView { observedFacts=Map.copyOf(observedFacts); }
    }
    public record EventView(String id,Instant at,String kind,String state,String incidentId,String detail) {}
    public record CommandView(String id,String incidentId,String action,Instant expiresAt) {}
    public record CommandResult(boolean completed,boolean applied,String detail) {}
    public record NotificationConfiguration(String appId,String secret,String chatId) {
        public NotificationConfiguration {
            if (appId==null || appId.isBlank() || secret==null || secret.isBlank() || chatId==null || !chatId.matches("oc_[a-zA-Z0-9]+"))
                throw new IllegalArgumentException("explicit notification recipient and bot credentials required");
        }
        @Override public String toString() { return "NotificationConfiguration[configured]"; }
    }
    public record NotificationView(long pending,long sent,long failed,List<String> failures) {}
    public record HypothesisView(String id,String cause,String assessment,String explanation,List<String> supportRefs,
                                 List<String> counterRefs,List<String> missingEvidence,List<String> alternatives,List<String> nextProbes) {}
    public record EvidenceView(String id,String probe,Instant at,String source,String quality,String unit,String summary,String limitation) {}
    public record DiagnosisView(String applicationId,String origin,String failureType,String summary,List<HypothesisView> hypotheses,
                                List<EvidenceView> evidence,String decision,String action,Instant at,Path artifact) {}
    private final ManagedRegistrationStore registrations;
    private final CommandExecutor commands;
    private final Clock clock;
    public ManagedOperationsService(Path stateRoot,CommandExecutor commands,Clock clock) throws IOException {
        registrations=new ManagedRegistrationStore(stateRoot,clock); this.commands=Objects.requireNonNull(commands); this.clock=clock;
    }
    public static ManagedOperationsService local(Path stateRoot) throws IOException {
        return new ManagedOperationsService(stateRoot,new ProcessCommandExecutor(),Clock.systemUTC());
    }
    public List<String> applications() throws IOException { return registrations.applications(); }
    public void configureMetrics(String id,URI endpoint) throws Exception {
        var app=registrations.read(id).application(); metricStore(id).configure(app,endpoint,clock.instant());
    }
    public void importChange(String id,Path input,String operator) throws Exception {
        var app=registrations.read(id).application();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        byte[] bytes; try (var stream=Files.newInputStream(input)) { bytes=stream.readNBytes(8193); }
        if (bytes.length>8192) throw new IllegalArgumentException("change import exceeds byte cap");
        var change=json.readValue(bytes,EvidenceEnvelope.ChangeRecord.class);
        var sanitized=new EvidenceEnvelope.ChangeRecord(change.id(),change.applicationId(),change.applicationVersion(),change.environment(),change.service(),
            change.occurredAt(),LogSanitizer.sanitize(change.configurationVersion()),LogSanitizer.sanitizeAll(change.summary()).text(),operator);
        changes(id).importChange(app,sanitized);
    }
    public DiagnosisView diagnose(String id,LLMProvider provider) throws Exception {
        var config=registrations.read(id); check(id); var directory=registrations.directory(id);
        try (var recorder=new FileRunRecorder(directory);
             var observer=observer(id,config.target())) {
            var agent=new OpsDecisionAgent(provider,directory.resolve("agent"),recorder,clock,OpsDecisionAgent.Limits.defaults(),
                OpsDecisionAgent.Guidance.REVIEWED_PLAYBOOKS,OpsDecisionAgent.ModelSettings.multisourceDefaults(),OpsDecisionAgent.Profile.MULTISOURCE);
            var baseline=ManagedEvidenceCollector.initial(config.application(),observer,clock);
            var outcome=agent.decide(config.application(),observer,com.clawkit.tools.control.ExecutionControl.none(),
                new OpsDecisionAgent.DecisionContext("diagnostic-"+UUID.randomUUID(),1,null,baseline));
            diagnoses(id).save(config.application(),outcome,clock.instant());
            return diagnosis(id);
        }
    }
    public DiagnosisView diagnosis(String id) throws Exception {
        registrations.read(id); var store=diagnoses(id); var record=store.latest(id);
        if (record==null) return new DiagnosisView(id,"NONE",null,"尚无诊断；运行 diagnose 调查当前状态。",List.of(),List.of(),null,null,null,store.latestPath());
        var report=record.diagnosis();
        return new DiagnosisView(id,record.origin().name(),record.failureType(),report==null ? "调查未产生有效诊断，保留证据并交接人工。" : report.summary(),
            report==null ? List.of() : report.hypotheses().stream().map(h -> new HypothesisView(h.id(),h.cause().name(),h.assessment().name(),h.explanation(),
                h.supportRefs(),h.counterRefs(),h.missingEvidence(),h.alternativeIds(),h.nextProbes().stream().map(Enum::name).toList())).toList(),
            record.evidence().stream().map(e -> { var o=e.observation(); var c=o.payload()==null ? null : o.payload().collection();
                String facts=o.payload()==null || o.payload().resources()==null ? "" : " "+o.payload().resources();
                return new EvidenceView(e.id(),o.probe().name(),o.observedAt(),c==null ? "LEGACY" : c.source().name(),
                    c==null ? "LEGACY" : c.quality().name(),c==null ? "" : c.unit(),o.detail()+facts,c==null ? "来源元数据不可得" : c.limitation()); }).toList(),
            record.decision().disposition().name(),record.decision().playbook()==null ? null : action(record.decision().playbook()),record.at(),store.latestPath());
    }
    public StatusView register(RegistrationRequest request) throws Exception {
        var app=new ManagedApplication(request.id(),"local-isolated",request.project(),request.service(),1,request.stateless(),
            ManagedApplication.DesiredState.RUNNING,null,request.healthUri(),request.businessUri(),request.businessMarker(),request.checkInterval(),Duration.ofSeconds(90));
        var target=IsolatedComposeClient.register(commands,request.composeFile(),request.context(),request.project(),request.service(),request.dependencies());
        registrations.register(app,target); return status(app.id());
    }
    public StatusView setPolicy(String id,String mode,Set<String> actions,Duration validity,String operator,String reviewNote) throws Exception {
        var permission=switch(mode.toLowerCase(Locale.ROOT)) { case "observe" -> ActionPolicy.Mode.OBSERVE; case "ask" -> ActionPolicy.Mode.ASK;
            case "limited-auto" -> ActionPolicy.Mode.LIMITED_AUTO; default -> throw new IllegalArgumentException("permission must be observe, ask or limited-auto"); };
        Set<OpsDecision.Playbook> playbooks=new HashSet<>();
        for (String action:actions) playbooks.add(switch(action) { case "start" -> OpsDecision.Playbook.START_STOPPED_V1;
            case "restart" -> OpsDecision.Playbook.RESTART_UNHEALTHY_V1; default -> throw new IllegalArgumentException("action must be start or restart"); });
        var config=registrations.read(id);
        if (permission==ActionPolicy.Mode.LIMITED_AUTO) {
            var container=new IsolatedComposeClient(config.target(),commands).service();
            if (container.writableMount() || container.restarting() || !(container.restartPolicy().isEmpty() || container.restartPolicy().equals("no")))
                throw new IllegalArgumentException("target has persistent mounts or native restart competition");
        }
        registrations.setPolicy(id,permission,playbooks,validity,operator,reviewNote); return status(id);
    }
    public StatusView status(String id) throws Exception {
        var config=registrations.read(id); var app=config.application(); var policy=config.policy(); var snapshot=incidents(id).read(); var current=snapshot.current();
        var degradation=new ManagedControlStore(registrations.directory(id).resolve("execution/control")).degradation(id);
        String permission=policy.mode().name();
        if (policy.mode()!=ActionPolicy.Mode.OBSERVE && degradation.isPresent()) permission="ASK";
        if (!clock.instant().isBefore(policy.expiresAt())) permission="EXPIRED";
        var latest=incidents(id).observations();
        var observations=latest==null ? current==null ? List.<ManagedObserver.Observation>of()
            : current.evidence().stream().map(DecisionEvidence::observation).toList() : latest.observations();
        // Display the actual collection time, never the controller heartbeat or an old pre-repair decision as fresh health.
        Instant observedAt=observations.stream().map(ManagedObserver.Observation::observedAt).min(Comparator.naturalOrder()).orElse(null);
        return new StatusView(id,app.composeProject()+"/"+app.service(),app.desiredState().name(),incidents(id).requestedMode().name(),permission,
            policy.qualification().name(),policy.expiresAt(),current==null ? null : current.id(),current==null ? "NO_INCIDENT" : current.state().name(),
            current==null || current.decision()==null ? null : current.decision().disposition().name(),
            current==null || current.decision()==null || current.decision().playbook()==null ? null : action(current.decision().playbook()),
            current==null ? null : current.decision()==null ? current.detail() : current.decision().reason(),
            current==null || current.repair()==null ? null : current.repair().status().name(),degradation.map(ManagedControlStore.Degradation::reason).orElse(null),
            observedAt,current==null ? 0 : current.observations(),current==null ? 0 : current.decisions(),registrations.directory(id),incidents(id).processActive(),
            observations.stream().collect(java.util.stream.Collectors.toMap(
                e -> e.probe().name(),e -> e.status().name(),(first,last) -> last)));
    }
    public List<EventView> events(String id,int limit) throws Exception {
        registrations.read(id);
        return incidents(id).recentEvents(limit).stream().map(e -> new EventView(e.id(),e.at(),e.kind(),e.state().name(),e.incidentId(),e.detail())).toList();
    }
    /** Configuration and pinned environment check only; neither registration nor checks can repair. */
    public StatusView check(String id) throws Exception {
        var config=registrations.read(id); new IsolatedComposeClient(config.target(),commands).service(); return status(id);
    }
    public void requestMode(String id,String mode) throws Exception {
        registrations.read(id);
        incidents(id).requestMode(ManagedIncidentStore.Mode.valueOf(mode.toUpperCase(Locale.ROOT)),clock.instant());
    }
    public CommandView requestCommand(String id,String incidentId,boolean approve,String operator) throws Exception {
        var config=registrations.read(id); var incident=incidents(id).read().current();
        if (incident==null || !incident.id().equals(incidentId)) throw new IllegalArgumentException("choose the current awaiting incident");
        if (!incident.applicationId().equals(config.application().id())) throw new IllegalArgumentException("incident target differs");
        var command=inbox(id).enqueue(approve ? ManagedCommandInbox.Type.APPROVE : ManagedCommandInbox.Type.REJECT,incident,operator);
        return new CommandView(command.id(),command.incidentId(),action(command.playbook()),command.expiresAt());
    }
    public CommandResult commandResult(String id,String commandId) throws Exception {
        registrations.read(id); var receipt=inbox(id).receipt(commandId);
        return receipt==null ? new CommandResult(false,false,"queued") : new CommandResult(true,receipt.applied(),receipt.detail());
    }
    public RunningSession open(String id,LLMProvider provider,Consumer<EventView> eventSink) throws Exception {
        return open(id,provider,eventSink,null);
    }
    public RunningSession open(String id,LLMProvider provider,Consumer<EventView> eventSink,NotificationConfiguration notifications) throws Exception {
        var config=registrations.read(id); check(id);
        return new RunningSession(id,config,provider,eventSink,notifications);
    }
    public NotificationView notifications(String id) throws IOException {
        registrations.read(id);
        var entries=new com.clawkit.ops.loop.notify.NotificationOutbox(registrations.directory(id).resolve("notifications/outbox")).listAll();
        return new NotificationView(entries.stream().filter(e -> Set.of(com.clawkit.ops.loop.notify.NotificationOutbox.State.PENDING,
                com.clawkit.ops.loop.notify.NotificationOutbox.State.DISPATCHING,com.clawkit.ops.loop.notify.NotificationOutbox.State.RETRYABLE_FAILED).contains(e.state())).count(),
            entries.stream().filter(e -> e.state()==com.clawkit.ops.loop.notify.NotificationOutbox.State.SENT).count(),
            entries.stream().filter(e -> e.state()==com.clawkit.ops.loop.notify.NotificationOutbox.State.PERMANENT_FAILED).count(),
            entries.stream().filter(e -> e.failureReason()!=null).limit(5).map(com.clawkit.ops.loop.notify.NotificationOutbox.Entry::failureReason).toList());
    }
    private ManagedIncidentStore incidents(String id) throws IOException { return new ManagedIncidentStore(registrations.directory(id).resolve("controller"),id); }
    private ManagedCommandInbox inbox(String id) throws IOException { return new ManagedCommandInbox(registrations.directory(id).resolve("inbox"),clock); }
    private ManagedChangeStore changes(String id) throws IOException { return new ManagedChangeStore(registrations.directory(id).resolve("changes"),clock); }
    private ManagedDiagnosisStore diagnoses(String id) throws IOException { return new ManagedDiagnosisStore(registrations.directory(id).resolve("diagnoses")); }
    private ManagedMetricStore metricStore(String id) throws IOException { return new ManagedMetricStore(registrations.directory(id).resolve("metrics")); }
    private ComposeManagedAdapter observer(String id,IsolatedComposeClient.Target target) throws IOException {
        var config=metricStore(id).read(registrations.read(id).application());
        var source=config==null || config.endpoint()==null ? null : new PrometheusMetricSource(config.endpoint(),target,clock);
        return new ComposeManagedAdapter(new IsolatedComposeClient(target,commands),clock,changes(id),source);
    }
    private static String action(OpsDecision.Playbook playbook) { return playbook==OpsDecision.Playbook.START_STOPPED_V1 ? "start" : "restart"; }
    private ManagedRegistrationStore.Registration current(String id,IsolatedComposeClient.Target pinned) {
        try {
            var config=registrations.read(id);
            if (!config.target().equals(pinned)) throw new IOException("registered target changed during control; restart after review");
            return config;
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public final class RunningSession implements AutoCloseable {
        private final String id;
        private final FileRunRecorder recorder;
        private final ComposeManagedAdapter adapter;
        private final ManagedRepairExecutor executor;
        private final ManagedIncidentController controller;
        private final ManagedCommandInbox queue;
        private final ManagedLifecycleNotifier notifier;
        private final ManagedFeishuTransport notificationTransport;
        private final ScheduledExecutorService lifecycle=worker("lifecycle"),humanCommands=worker("human-commands"),delivery=worker("delivery");
        private final AtomicBoolean closed=new AtomicBoolean();
        private final AtomicBoolean started=new AtomicBoolean();
        private volatile boolean stopRequested;
        private volatile String failure;
        private volatile ManagedIncidentStore.Mode appliedMode;
        private RunningSession(String id,ManagedRegistrationStore.Registration config,LLMProvider provider,Consumer<EventView> sink,NotificationConfiguration notifications) throws Exception {
            this.id=id; var target=config.target(); Path directory=registrations.directory(id); queue=inbox(id);
            recorder=new FileRunRecorder(directory);
            adapter=observer(id,target);
            var verifier=new IndependentManagedVerifier(clock,IndependentManagedVerifier.Settings.defaults(),d -> Thread.sleep(d.toMillis()));
            executor=new ManagedRepairExecutor(directory.resolve("execution"),clock,recorder,verifier);
            var agent=new OpsDecisionAgent(provider,directory.resolve("agent"),recorder,clock,OpsDecisionAgent.Limits.defaults(),
                OpsDecisionAgent.Guidance.REVIEWED_PLAYBOOKS,OpsDecisionAgent.ModelSettings.multisourceDefaults(),OpsDecisionAgent.Profile.MULTISOURCE);
            notificationTransport=notifications==null ? null : new ManagedFeishuTransport(notifications.appId(),notifications.secret());
            notifier=notifications==null ? null : new ManagedLifecycleNotifier(directory.resolve("notifications"),config.application(),
                notifications.chatId(),notificationTransport,clock);
            try {
                controller=new ManagedIncidentController(() -> current(id,target).application(),() -> current(id,target).policy(),
                    () -> observer(id,target),(app,observations,control,context) -> {
                        var outcome=agent.decide(app,observations,control,context);
                        try { diagnoses(id).save(app,outcome,clock.instant()); }
                        catch (Exception e) { org.slf4j.LoggerFactory.getLogger(ManagedOperationsService.class)
                            .warn("Diagnostic view projection unavailable: {}",e.getClass().getSimpleName()); }
                        return outcome; // Controller's decision artifact remains authoritative.
                    },adapter,executor,verifier,
                    incidents(id),new JdkAutomationTaskScheduler(1),ManagedIncidentController.Settings.defaults(),clock,event -> {
                        if (notifier!=null) {
                            try { notifier.enqueue(event); } catch (IOException e) { throw new UncheckedIOException(e); }
                        }
                        if (!event.kind().equals("OBSERVATION_MERGED")) sink.accept(new EventView(event.id(),event.at(),event.kind(),event.state().name(),event.incidentId(),event.detail()));
                    });
            } catch (Exception e) { executor.close(); adapter.close(); recorder.close(); lifecycle.shutdown(); humanCommands.shutdown(); delivery.shutdown();
                if (notificationTransport!=null) notificationTransport.close(); throw e; }
        }
        public void start() throws Exception {
            if (!started.compareAndSet(false,true)) throw new IllegalStateException("control session already started");
            controller.start(); appliedMode=ManagedIncidentStore.Mode.RUNNING;
            lifecycle.scheduleWithFixedDelay(this::pollMode,100,250,TimeUnit.MILLISECONDS);
            humanCommands.scheduleWithFixedDelay(this::pollCommands,100,250,TimeUnit.MILLISECONDS);
            if (notifier!=null) delivery.scheduleWithFixedDelay(this::pollDelivery,100,1_000,TimeUnit.MILLISECONDS);
        }
        public void once() throws Exception {
            incidents(id).requestMode(ManagedIncidentStore.Mode.RUNNING,clock.instant());
            controller.tick();
            if (notifier!=null) pollDelivery();
        }
        private void pollDelivery() {
            if (closed.get()) return;
            try { notifier.flush(); } catch (Exception ignored) { /* Delivery state/payloads remain local; never change incident permission or outcome. */ }
        }
        private void pollMode() {
            if (closed.get()) return;
            try {
                var requested=incidents(id).requestedMode();
                if (requested==appliedMode) return;
                appliedMode=controller.synchronizeRequestedMode();
                if (appliedMode==ManagedIncidentStore.Mode.STOPPED) stopRequested=true;
            } catch (Exception e) { failure="lifecycle unavailable: "+e.getClass().getSimpleName(); try { controller.pause(); } catch (Exception ignored) {} }
        }
        private void pollCommands() {
            if (closed.get()) return;
            try {
                for (var command:queue.pending()) {
                    if (closed.get()) return;
                    try { controller.apply(command); queue.complete(command,true,"human command applied; inspect incident for execution outcome"); }
                    catch (Exception e) { queue.complete(command,false,"human command refused: "+e.getClass().getSimpleName()); }
                }
            } catch (Exception e) { failure="human command queue unavailable: "+e.getClass().getSimpleName(); try { controller.pause(); } catch (Exception ignored) {} }
        }
        public boolean stopRequested() { return stopRequested; }
        public String failure() { return failure==null ? controller.lastFailure() : failure; }
        @Override public void close() throws Exception {
            if (!closed.compareAndSet(false,true)) return;
            lifecycle.shutdown(); humanCommands.shutdown(); delivery.shutdown();
            try { controller.close(); }
            finally { try { executor.close(); } finally { adapter.close(); recorder.close(); if (notificationTransport!=null) notificationTransport.close(); } }
        }
    }
    private static ScheduledExecutorService worker(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> { var thread=new Thread(r,"clawkit-managed-"+name); thread.setDaemon(true); return thread; });
    }
}
