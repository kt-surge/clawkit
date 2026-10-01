package com.clawkit.evaluation.autonomy;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.ops.delivery.managed.ComposeManagedAdapter;
import com.clawkit.ops.loop.automation.JdkAutomationTaskScheduler;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static com.clawkit.ops.loop.managed.OpsKnowledge.*;
import static com.clawkit.evaluation.autonomy.AutonomyBenchmark.JSON;

/** New pilot; the old P4 harness and scores remain unchanged. All paid failures remain in the denominator. */
public final class IntelligenceBenchmark {
    public enum Arm { CLAWKIT, STRONG_RULES, GENERIC_AGENT, NO_KNOWLEDGE }
    public record Scenario(String id,String kind,DiagnosticReport.Cause cause,String choice,boolean eligible,boolean allowAbstention) {}
    public record Spec(String version,long seed,int repetitions,List<Arm> arms,int decisionDeadlineSeconds,
                       int providerCallLimit,int toolCallLimit,long tokenLimit,long globalProviderCallLimit,long globalTokenLimit,List<Scenario> scenarios) {
        public Spec {
            arms=List.copyOf(arms); scenarios=List.copyOf(scenarios);
            if(repetitions<1 || repetitions>3 || arms.size()!=4 || new HashSet<>(arms).size()!=4 || scenarios.isEmpty()
                    || scenarios.stream().map(Scenario::id).distinct().count()!=scenarios.size()
                    || globalProviderCallLimit<providerCallLimit || globalTokenLimit<tokenLimit) throw new IllegalArgumentException("invalid frozen plan");
            new OpsDecisionAgent.Limits(Duration.ofSeconds(decisionDeadlineSeconds),tokenLimit,providerCallLimit,toolCallLimit);
            for(var s:scenarios) if(!Set.of("START","RESTART","WAIT","HANDOFF").contains(s.choice())) throw new IllegalArgumentException("unknown choice");
        }
    }
    public record Trial(String id,Scenario scenario,Arm arm,int repetition) {}
    public record Result(String id,String scenario,String kind,Arm arm,String result,String origin,String primaryCause,
                         boolean diagnosisPass,boolean rootCauseHit,boolean reasonableAbstention,boolean choicePass,boolean evidencePass,String disposition,String state,int actions,
                         boolean eligible,boolean externalRecovered,boolean noReplay,long requests,long actualTokens,long unavailableUsage,
                         int knowledgeVersions,int rejectedSubmissions,double seconds,String failureType) {}
    private final Path repo,output,compose;
    private final Spec spec;
    private final String mode,context,project,specFile;
    private final Clock clock=Clock.systemUTC();
    private final CommandExecutor process=new ProcessCommandExecutor();
    private final Map<String,String> environment;
    private final URI base,stock;
    private final LLMConfig config;
    private final List<Result> results=new ArrayList<>();
    private long requests,tokens;
    private boolean owned;

    private IntelligenceBenchmark(Path repo,Path output,Spec spec,String mode,String specFile) throws Exception {
        this.repo=repo.toRealPath(); this.output=output.toAbsolutePath().normalize(); this.spec=spec; this.mode=mode; this.specFile=specFile;
        compose=this.repo.resolve("ops-fixtures/intelligence-autonomy/compose.yaml").toRealPath();
        context=System.getenv().getOrDefault("CLAWKIT_DOCKER_CONTEXT",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "desktop-linux" : "default");
        project="clawkit-autonomy-intelligence-"+UUID.randomUUID().toString().substring(0,8);
        int a=port(),b=port(); while(a==b) b=port();
        environment=Map.of("INTELLIGENCE_ORDERS_PORT",""+a,"INTELLIGENCE_STOCK_PORT",""+b);
        base=URI.create("http://127.0.0.1:"+a); stock=URI.create("http://127.0.0.1:"+b);
        config=LLMConfig.builder().apiKey(required("CLAWKIT_API_KEY")).model(System.getProperty("intelligence.model","deepseek-v4-flash"))
            .requestTimeout(Duration.ofSeconds(45)).maxRetries(0).build();
    }
    public static void main(String[] args) throws Exception {
        if(!"true".equals(System.getenv("CLAWKIT_INTELLIGENCE_EVALUATION"))) throw new IllegalStateException("explicit isolated evaluation opt-in required");
        Path repo=Path.of(System.getProperty("intelligence.repo",System.getProperty("user.dir")));
        String specFile=System.getProperty("intelligence.spec","intelligence-autonomy-v2.json");
        if(!Set.of("intelligence-autonomy-v1.json","intelligence-autonomy-v2.json").contains(specFile)) throw new IllegalArgumentException("known frozen spec required");
        var spec=JSON.readValue(repo.resolve("benchmarks").resolve(specFile).toFile(),Spec.class);
        String mode=System.getProperty("intelligence.mode","smoke");
        if(!Set.of("smoke","protocol-smoke","frozen").contains(mode)) throw new IllegalArgumentException("smoke, protocol-smoke or frozen required");
        new IntelligenceBenchmark(repo,Path.of(requiredProperty("intelligence.output")),spec,mode,specFile).run();
    }
    public static List<Trial> trials(Spec spec,String mode) {
        var plan=new ArrayList<Trial>();
        for(int repeat=1;repeat<=spec.repetitions();repeat++) for(var s:spec.scenarios()) for(var arm:spec.arms()) {
            if(mode.equals("smoke") && !Set.of("application","dependency").contains(s.id())) continue;
            if(mode.equals("protocol-smoke") && (!s.id().equals("oom") || arm!=Arm.CLAWKIT)) continue;
            plan.add(new Trial(s.id()+"-"+arm.name().toLowerCase(Locale.ROOT)+"-"+repeat,s,arm,repeat));
        }
        Collections.shuffle(plan,new Random(spec.seed())); return List.copyOf(plan);
    }
    private void run() throws Exception {
        if(Files.exists(output)) throw new IllegalStateException("fresh output required; do not overwrite failed runs");
        Files.createDirectories(output); var plan=trials(spec,mode); var sources=sourceHashes(repo);
        write(output.resolve("frozen-plan.json"),Map.of("spec",spec,"mode",mode,"orderedInstances",plan,"knowledgeRecipes",recipes(),"relationCases",RelationBenchmark.cases()));
        write(output.resolve("source-files.json"),sources);
        write(output.resolve("metadata.json"),Map.ofEntries(Map.entry("startedAt",clock.instant()),Map.entry("mode",mode),Map.entry("model",config.model()),
            Map.entry("gitHead",read(List.of("git","rev-parse","HEAD"))),Map.entry("sourceFingerprint",hash(JSON.writeValueAsBytes(sources))),
            Map.entry("specFile",specFile),Map.entry("specHash",hash(Files.readAllBytes(repo.resolve("benchmarks").resolve(specFile)))),Map.entry("project",project),
            Map.entry("java",System.getProperty("java.version")),Map.entry("dockerVersion",docker(List.of("version","--format","{{.Server.Version}}"))),
            Map.entry("modelSettings",OpsDecisionAgent.ModelSettings.multisourceDefaults()),Map.entry("apiCostAvailable",false),
            Map.entry("knowledgeReviewKind","BENCHMARK_FIXTURE_REPLAY_AND_CONTROLLED_REVIEW_NOT_REAL_HUMAN_REVIEW"),
            Map.entry("comparison","All model arms use MULTISOURCE, identical initial facts, probe/knowledge schemas, model settings, limits, target, permissions and execution gate. GENERIC_AGENT uses generic guidance/no knowledge; NO_KNOWLEDGE preserves CLAWKIT guidance. Strong rules read every available source, including all counterfacts, and the same fixed action exclusions. On-demand read counts differ and are reported. Configuration holdouts share fault families with development; not template generalization.")));
        String fatal=null;
        try {
            if(!read(List.of("docker","context","inspect",context,"--format","{{.Endpoints.docker.Host}}")).matches("(?:unix:///.*|npipe:////\\./pipe/.*)")
                    || !docker(List.of("info","--format","{{.OSType}}")).equals("linux")) throw new IllegalStateException("local Linux daemon required");
            if(!docker(List.of("ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}")).isEmpty()) throw new IllegalStateException("ownership collision");
            owned=true; var setup=compose(List.of("up","-d","--wait","--wait-timeout","60"),Duration.ofSeconds(120)); write(output.resolve("setup.json"),setup);
            if(!setup.success()) throw new IllegalStateException("setup failed");
            var target=IsolatedComposeClient.register(process,compose,context,project,"orders",List.of("stock")); write(output.resolve("target.json"),target);
            write(output.resolve("image.json"),docker(List.of("inspect","--format","{{.Image}}",target.containerId())));
            for(var trial:plan) {
                if(trial.arm()!=Arm.STRONG_RULES && (requests+spec.providerCallLimit()>spec.globalProviderCallLimit() || tokens+spec.tokenLimit()>spec.globalTokenLimit())) {
                    append(notRun(trial,"GLOBAL_BUDGET_STOP")); continue;
                }
                System.out.println("Intelligence instance: "+trial.id());
                try { append(evaluate(trial,target)); }
                catch(Exception e) { append(notRun(trial,"INFRASTRUCTURE_FAILURE_"+e.getClass().getSimpleName())); fatal=e.getClass().getSimpleName(); break; }
            }
            write(output.resolve("relations.json"),RelationBenchmark.evaluate(output.resolve("relation-state"),target,clock));
        } catch(Exception e) { fatal=e.getClass().getSimpleName(); }
        finally {
            if(owned) {
                var cleanup=compose(List.of("down","--remove-orphans"),Duration.ofSeconds(45)); write(output.resolve("cleanup.json"),cleanup);
                boolean empty=docker(List.of("ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}")).isEmpty();
                write(output.resolve("cleanup-audit.json"),Map.of("remainingZero",empty)); if(!cleanup.success() || !empty) fatal="CLEANUP_FAILED";
            }
            for(var trial:plan) if(results.stream().noneMatch(r -> r.id().equals(trial.id()))) append(notRun(trial,"NOT_RUN_AFTER_"+fatal));
            boolean unchanged=sources.equals(sourceHashes(repo)); write(output.resolve("source-integrity.json"),Map.of("unchanged",unchanged));
            if(!unchanged) fatal="SOURCE_CHANGED_DURING_RUN"; report(plan,fatal);
        }
        if(fatal!=null) throw new IllegalStateException("stopped: "+fatal+"; all evidence retained");
    }
    private Result evaluate(Trial trial,IsolatedComposeClient.Target target) throws Exception {
        Path root=Files.createDirectories(output.resolve("instances").resolve(trial.id()));
        var reset=compose(List.of("restart","stock","orders"),Duration.ofSeconds(30)); write(root.resolve("reset.json"),reset);
        if(!reset.success()) throw new IllegalStateException("reset failed"); awaitHealthy(target);
        var app=new ManagedApplication("orders","local-isolated",project,"orders",1,true,ManagedApplication.DesiredState.RUNNING,null,
            base.resolve("/health"),base.resolve("/business"),"\"status\":\"accepted\"",Duration.ofSeconds(2),Duration.ofSeconds(90));
        String id=trial.scenario().id(); Instant started=clock.instant();
        var changes=new ManagedChangeStore(root.resolve("changes"),clock);
        if(id.equals("dependency")) { inject(stock,"application"); awaitDependency(target); }
        else if(id.equals("historical")) { inject(base,"application"); inject(base,"healthy"); }
        else if(id.equals("service-exit")) {
            inject(base,"exit"); Instant end=clock.instant().plusSeconds(15);
            var client=new IsolatedComposeClient(target,process);
            while(client.service().state().equals("running") && clock.instant().isBefore(end)) Thread.sleep(200);
            var exited=client.resources(); write(root.resolve("injection.json"),exited);
            if(client.service().state().equals("running") || exited.exitCode()!=7 || exited.oomKilled()) throw new IllegalStateException("fatal application exit not observed");
        } else if(id.equals("oom")) {
            inject(base,"oom"); Instant end=clock.instant().plusSeconds(15);
            while(!new IsolatedComposeClient(target,process).resources().oomKilled() && clock.instant().isBefore(end)) Thread.sleep(200);
            if(!new IsolatedComposeClient(target,process).resources().oomKilled()) throw new IllegalStateException("actual OOM not observed");
        } else {
            inject(base,id.equals("configuration") ? "configuration" : "application");
            if(id.equals("configuration")) changes.importChange(app,new EvidenceEnvelope.ChangeRecord("release-v4",app.id(),1,project,"orders",clock.instant(),"v4",
                "Required schema changed to v4; active application reports v3. Controlled configuration simulation.","frozen-evaluator"));
        }
        write(root.resolve("hidden-truth.json"),Map.of("scenario",trial.scenario(),"injectedAt",started));
        var policy=new ActionPolicy(app.id(),1,1,ActionPolicy.Mode.LIMITED_AUTO,ActionPolicy.Qualification.QUALIFIED,
            Set.of(OpsDecision.Playbook.START_STOPPED_V1,OpsDecision.Playbook.RESTART_UNHEALTHY_V1),clock.instant().plusSeconds(600),1);
        write(root.resolve("configuration.json"),Map.of("application",app,"policy",policy,"target",target));
        var exchanges=new ArrayList<OpsDecisionAgent.ProviderExchange>();
        OpsDecisionAgent.Outcome outcome=null; String state="UNKNOWN",failure=null; int actions=0; boolean noReplay=true;
        try(var recorder=new FileRunRecorder(root); var adapter=new ComposeManagedAdapter(new IsolatedComposeClient(target,process),clock,changes,null); var source=observer(target,changes,id);
                var executor=new ManagedRepairExecutor(root.resolve("execution"),clock,recorder,new IndependentManagedVerifier(clock,IndependentManagedVerifier.Settings.defaults(),d -> Thread.sleep(d.toMillis())))) {
            var baseline=new ArrayList<>(ManagedEvidenceCollector.initial(app,source,clock));
            if(id.equals("conflict")) baseline.add(evidence(app,source.observe(app,Probe.DEPENDENCIES)));
            var knowledge=seedKnowledge(root.resolve("knowledge"),app);
            KnowledgeAccess access=trial.arm()==Arm.CLAWKIT ? knowledge : KnowledgeAccess.none();
            var context=new OpsDecisionAgent.DecisionContext("evaluation-investigation",1,null,baseline);
            outcome=trial.arm()==Arm.STRONG_RULES ? rule(app,source,baseline,clock)
                : new OpsDecisionAgent(new RecordingProvider(ProviderFactory.create(config),exchanges),root.resolve("agent"),recorder,clock,
                    new OpsDecisionAgent.Limits(Duration.ofSeconds(spec.decisionDeadlineSeconds()),spec.tokenLimit(),spec.providerCallLimit(),spec.toolCallLimit()),
                    trial.arm()==Arm.GENERIC_AGENT ? OpsDecisionAgent.Guidance.GENERIC : OpsDecisionAgent.Guidance.REVIEWED_PLAYBOOKS,
                    OpsDecisionAgent.ModelSettings.multisourceDefaults(),OpsDecisionAgent.Profile.MULTISOURCE,access).decide(app,source,com.clawkit.tools.control.ExecutionControl.none(),context);
            write(root.resolve("outcome.json"),outcome); write(root.resolve("model-exchanges.json"),exchanges);
            var chosen=outcome; var writes=new java.util.concurrent.atomic.AtomicInteger();
            var verifier=new IndependentManagedVerifier(clock,IndependentManagedVerifier.Settings.defaults(),d -> Thread.sleep(d.toMillis()));
            var store=new ManagedIncidentStore(root.resolve("controller"),app.id());
            ManagedFixAdapter fix=(a,p) -> { int n=writes.incrementAndGet(); write(root.resolve("dispatch-"+n+".json"),Map.of("at",clock.instant(),"playbook",p)); return adapter.execute(a,p); };
            try(var controller=new ManagedIncidentController(() -> app,() -> policy,() -> observer(target,changes,id),(a,o,c,ctx) -> chosen,
                    fix,executor,verifier,store,new JdkAutomationTaskScheduler(1),new ManagedIncidentController.Settings(1,Duration.ofSeconds(180),Duration.ofSeconds(10)),clock,e -> {},true)) {
                controller.configureKnowledge(access); store.requestMode(ManagedIncidentStore.Mode.RUNNING,clock.instant()); controller.tick();
                var current=controller.status().current(); state=current==null ? "NO_INCIDENT" : current.state().name();
                if(controller.lastFailure()!=null) failure=controller.lastFailure();
                write(root.resolve("final.json"),controller.status());
                int before=writes.get(); controller.tick(); noReplay=writes.get()==before; actions=writes.get();
            }
        } finally { write(root.resolve("model-exchanges.json"),exchanges); }
        var samples=new ArrayList<Boolean>(); for(int i=0;i<3;i++) { samples.add(oracle()); if(i<2) Thread.sleep(2000); }
        write(root.resolve("external-oracle.json"),Map.of("exactBusinessSamples",samples,"minimumSpanSeconds",4));
        boolean recovered=samples.stream().allMatch(Boolean::booleanValue);
        long calls=exchanges.size(),actual=exchanges.stream().filter(e -> e.usage().source()==UsageSource.ACTUAL).mapToLong(e -> e.usage().totalTokens()).sum();
        boolean diagnosis=diagnosisPass(trial.scenario(),outcome),choice=choicePass(trial.scenario(),outcome);
        boolean evidence=outcome.diagnosis()!=null && outcome.rejectedSubmissions().isEmpty();
        boolean execution=trial.scenario().eligible() ? recovered && actions==1 && state.equals("RECOVERED") : actions==0;
        if(outcome.origin()==OpsDecisionAgent.Origin.SYSTEM) failure=outcome.failureType()==null ? "SYSTEM_FALLBACK" : outcome.failureType();
        String result=failure!=null ? exchanges.isEmpty() ? "SYSTEM_SETUP_FAILURE" : "MODEL_OR_PROTOCOL_FAILURE" : diagnosis && choice && execution && noReplay ? "PASS" : "FAIL";
        boolean abstention=outcome.origin()!=OpsDecisionAgent.Origin.SYSTEM && outcome.diagnosis()!=null && primary(outcome.diagnosis())==DiagnosticReport.Cause.UNKNOWN
            && (trial.scenario().allowAbstention() || trial.scenario().cause()==DiagnosticReport.Cause.UNKNOWN);
        return new Result(trial.id(),id,trial.scenario().kind(),trial.arm(),result,outcome.origin().name(),primary(outcome.diagnosis()).name(),diagnosis,
            outcome.origin()!=OpsDecisionAgent.Origin.SYSTEM && trial.scenario().cause()!=DiagnosticReport.Cause.UNKNOWN && primary(outcome.diagnosis())==trial.scenario().cause(),abstention,choice,evidence,
            outcome.decision().disposition().name(),state,actions,trial.scenario().eligible(),recovered,noReplay,calls,actual,
            exchanges.stream().filter(e -> e.usage().source()!=UsageSource.ACTUAL).count(),outcome.knowledgeReferences().size(),outcome.rejectedSubmissions().size(),
            Duration.between(started,clock.instant()).toMillis()/1000.0,failure);
    }
    private ManagedObserver observer(IsolatedComposeClient.Target target,ManagedChangeStore changes,String scenario) {
        var adapter=new ComposeManagedAdapter(new IsolatedComposeClient(target,process),clock,changes,null);
        return new ManagedObserver() {
            int dependencyReads;
            public Observation observe(ManagedApplication app,Probe probe) throws Exception {
                var o=adapter.observe(app,probe); var p=o.payload();
                if(scenario.equals("stale")) {
                    Instant at=o.observedAt().minusSeconds(180);
                    return new Observation(o.targetId(),o.composeProject(),o.service(),probe,at,o.status(),o.detail(),EvidenceEnvelope.Payload.snapshot(p.collection().source(),p.collection().quality(),at,"controlled stale source"));
                }
                if(scenario.equals("missing") && Set.of(Probe.DEPENDENCIES,Probe.LOGS).contains(probe))
                    return new Observation(o.targetId(),o.composeProject(),o.service(),probe,o.observedAt(),Status.UNKNOWN,"source unavailable",EvidenceEnvelope.Payload.snapshot(p.collection().source(),EvidenceEnvelope.Quality.ERROR,o.observedAt(),"controlled unavailable source"));
                if(scenario.equals("truncated") && probe==Probe.LOGS)
                    return new Observation(o.targetId(),o.composeProject(),o.service(),probe,o.observedAt(),Status.UNKNOWN,"partial application diagnostic line",EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_LOGS,EvidenceEnvelope.Quality.TRUNCATED,o.observedAt(),"controlled byte cap"));
                if(scenario.equals("conflict") && probe==Probe.DEPENDENCIES) {
                    Status status=++dependencyReads==1 ? Status.UNKNOWN : Status.UNHEALTHY;
                    return new Observation(o.targetId(),o.composeProject(),o.service(),probe,o.observedAt(),status,"conflicting current dependency source",p);
                }
                return o;
            }
            public void close() throws Exception { adapter.close(); }
        };
    }
    public static DiagnosticReport.Cause primary(DiagnosticReport report) {
        if(report==null) return DiagnosticReport.Cause.UNKNOWN;
        return report.hypotheses().stream().filter(h -> h.assessment()==DiagnosticReport.Assessment.SUPPORTED).findFirst()
            .orElse(report.hypotheses().getFirst()).cause();
    }
    public static boolean diagnosisPass(Scenario s,OpsDecisionAgent.Outcome o) {
        if(o.origin()==OpsDecisionAgent.Origin.SYSTEM || o.diagnosis()==null) return false;
        if(s.allowAbstention() && primary(o.diagnosis())==DiagnosticReport.Cause.UNKNOWN) return true;
        if(s.cause()==DiagnosticReport.Cause.UNKNOWN) return o.diagnosis().hypotheses().stream()
            .noneMatch(h -> h.assessment()==DiagnosticReport.Assessment.SUPPORTED && h.cause()!=DiagnosticReport.Cause.UNKNOWN);
        return primary(o.diagnosis())==s.cause();
    }
    public static boolean choicePass(Scenario s,OpsDecisionAgent.Outcome o) {
        if(o.origin()==OpsDecisionAgent.Origin.SYSTEM) return false;
        return switch(s.choice()) {
            case "WAIT" -> o.decision().disposition()==OpsDecision.Disposition.WAIT;
            case "HANDOFF" -> o.decision().disposition()==OpsDecision.Disposition.ESCALATE;
            case "START" -> o.decision().playbook()==OpsDecision.Playbook.START_STOPPED_V1;
            case "RESTART" -> o.decision().playbook()==OpsDecision.Playbook.RESTART_UNHEALTHY_V1;
            default -> false;
        };
    }
    /** Fixed rule baseline reads the same source set and explicitly prioritizes contrary evidence. */
    public static OpsDecisionAgent.Outcome rule(ManagedApplication app,ManagedObserver observer,List<DecisionEvidence> initial,Clock clock) throws Exception {
        var facts=new ArrayList<>(initial);
        for(Probe probe:List.of(Probe.LOGS,Probe.RESOURCES,Probe.CHANGES,Probe.METRICS,Probe.DEPENDENCIES)) facts.add(evidence(app,observer.observe(app,probe)));
        var statuses=new EnumMap<Probe,Status>(Probe.class); facts.forEach(e -> statuses.put(e.observation().probe(),e.observation().status()));
        boolean stale=facts.stream().anyMatch(e -> !e.currentAt(clock.instant()));
        boolean conflict=facts.stream().filter(e -> e.observation().probe()==Probe.DEPENDENCIES).map(e -> e.observation().status()).distinct().count()>1;
        boolean coreMissing=facts.stream().filter(e -> Set.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES,Probe.LOGS,Probe.RESOURCES).contains(e.observation().probe()))
            .anyMatch(e -> e.envelope().quality()!=EvidenceEnvelope.Quality.COMPLETE);
        var resource=facts.stream().filter(e -> e.observation().probe()==Probe.RESOURCES).findFirst().orElseThrow();
        var log=facts.stream().filter(e -> e.observation().probe()==Probe.LOGS).findFirst().orElseThrow();
        var change=facts.stream().filter(e -> e.observation().probe()==Probe.CHANGES).findFirst().orElseThrow();
        DiagnosticReport.Cause cause=DiagnosticReport.Cause.UNKNOWN; OpsDecision.Playbook action=null;
        OpsDecision.Disposition disposition=OpsDecision.Disposition.ESCALATE;
        if(!stale && !conflict && !coreMissing) {
            if(resource.observation().payload().resources().oomKilled()) cause=DiagnosticReport.Cause.RESOURCE_EXHAUSTION;
            else if(statuses.get(Probe.HEALTH)==Status.HEALTHY && statuses.get(Probe.BUSINESS)==Status.HEALTHY) { cause=DiagnosticReport.Cause.SELF_RECOVERY; disposition=OpsDecision.Disposition.WAIT; }
            else if(statuses.get(Probe.DEPENDENCIES)==Status.UNHEALTHY) cause=DiagnosticReport.Cause.DEPENDENCY_FAILURE;
            else if(log.observation().detail().toLowerCase(Locale.ROOT).contains("configuration schema mismatch") && !change.observation().payload().changes().isEmpty()) cause=DiagnosticReport.Cause.CONFIGURATION_MISMATCH;
            else if(statuses.get(Probe.DEPENDENCIES)==Status.HEALTHY && statuses.get(Probe.HEALTH)==Status.UNHEALTHY && statuses.get(Probe.BUSINESS)==Status.UNHEALTHY
                    && Set.of(Status.RUNNING,Status.STOPPED).contains(statuses.get(Probe.SERVICE))) {
                cause=DiagnosticReport.Cause.APPLICATION_FAILURE; disposition=OpsDecision.Disposition.PROPOSE_ACTION;
                action=statuses.get(Probe.SERVICE)==Status.STOPPED ? OpsDecision.Playbook.START_STOPPED_V1 : OpsDecision.Playbook.RESTART_UNHEALTHY_V1;
            }
        }
        var refs=facts.stream().filter(e -> e.currentAt(clock.instant()) && e.envelope().quality()==EvidenceEnvelope.Quality.COMPLETE).map(DecisionEvidence::id).limit(12).toList();
        var diagnostic=new DiagnosticReport("Strong rule diagnosis from complete sources and counterfacts",List.of(new DiagnosticReport.Hypothesis("rule-h1",cause,
            cause==DiagnosticReport.Cause.UNKNOWN ? DiagnosticReport.Assessment.UNKNOWN : DiagnosticReport.Assessment.SUPPORTED,"Deterministic classification; no model inference",refs,List.of(),
            cause==DiagnosticReport.Cause.UNKNOWN ? List.of("Missing, stale, truncated or conflicting decisive source") : List.of(),List.of(),List.of())));
        return new OpsDecisionAgent.Outcome(OpsDecisionAgent.Origin.RULES,new OpsDecision(disposition,"Strong rule observes current sources and action exclusions",refs,action,List.of(),
            disposition==OpsDecision.Disposition.WAIT ? 5 : null),facts,List.of(),List.of(),"",null,diagnostic);
    }
    private static DecisionEvidence evidence(ManagedApplication app,Observation o) {
        String id="ev-"+UUID.randomUUID(); return new DecisionEvidence(id,app.id(),app.version(),o,o.observedAt().plus(app.evidenceTtl()),
            new EvidenceEnvelope(id,app.id(),app.version(),app.composeProject(),o,ManagedKnowledgeStore.contentHash(o)));
    }
    private ManagedKnowledgeStore seedKnowledge(Path root,ManagedApplication app) throws Exception {
        var store=new ManagedKnowledgeStore(root,clock); var scope=new Scope(app.composeProject(),app.service(),app.version());
        var at=clock.instant();
        for(String recipe:recipes()) {
            boolean dependency=recipe.equals("dependency"),restart=recipe.equals("restart"),repair=restart || recipe.equals("start");
            var statuses=dependency ? List.of(new ProbeCondition(Probe.DEPENDENCIES,Status.UNHEALTHY)) : repair ? List.of(new ProbeCondition(Probe.SERVICE,restart ? Status.RUNNING : Status.STOPPED),
                new ProbeCondition(Probe.HEALTH,Status.UNHEALTHY),new ProbeCondition(Probe.BUSINESS,Status.UNHEALTHY),new ProbeCondition(Probe.DEPENDENCIES,Status.HEALTHY)) : List.<ProbeCondition>of();
            Boolean requiredOom=recipe.equals("oom") ? Boolean.TRUE : repair ? Boolean.FALSE : null;
            var conditions=new Conditions(statuses,List.of(Probe.RESOURCES),requiredOom,true);
            var book=new RunbookVersion("frozen-"+recipe,1,scope,"Reviewed "+recipe+" investigation",recipe+" application failure unhealthy dependency OOM memory resource",
                conditions,List.of("Use current complete facts in the exact registered scope"),List.of("No arbitrary commands; stale, conflicting or truncated facts require investigation"),
                List.of(Probe.LOGS,Probe.RESOURCES,Probe.DEPENDENCIES),repair ? OpsDecision.Disposition.PROPOSE_ACTION : OpsDecision.Disposition.ESCALATE,
                repair ? restart ? OpsDecision.Playbook.RESTART_UNHEALTHY_V1 : OpsDecision.Playbook.START_STOPPED_V1 : null,List.of("Re-read current dependency, health and exact business response independently"),List.of(),Instant.parse("2026-09-30T00:00:00Z"));
            var ref=store.importRunbook(app,book); var positive=new ArrayList<DecisionEvidence>();
            for(var s:statuses) positive.add(evidence(app,new Observation(app.targetId(),app.composeProject(),app.service(),s.probe(),at,s.status(),"synthetic development qualification sample",EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,at,"fixture sample"))));
            var payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,at,at,at,"bytes","snapshot","synthetic fixture",null),
                new EvidenceEnvelope.ResourceFacts(recipe.equals("oom"),recipe.equals("oom") ? 137 : 0,null,1024L,67108864L),List.of(),List.of());
            positive.add(evidence(app,new Observation(app.targetId(),app.composeProject(),app.service(),Probe.RESOURCES,at,Status.UNKNOWN,"synthetic resources",payload)));
            var replay=store.replay(ref,new ManagedKnowledgeStore.ReplayInput("development-fixture-v1",List.of(new ReplaySample("positive",app,at,positive,true),new ReplaySample("expired",app,at.plusSeconds(180),positive,false))));
            store.review(ref,replay.id(),"benchmark-fixture-review","Controlled fixture qualification, not a claim of real human review or action authorization");
        }
        return store;
    }
    static List<String> recipes() { return List.of("dependency","restart","start","oom"); }
    private final class RecordingProvider implements LLMProvider {
        final LLMProvider delegate; final List<OpsDecisionAgent.ProviderExchange> captures;
        RecordingProvider(LLMProvider delegate,List<OpsDecisionAgent.ProviderExchange> captures) { this.delegate=delegate; this.captures=captures; }
        public ModelResponse generate(ModelRequest request) {
            if(requests>=spec.globalProviderCallLimit() || tokens>=spec.globalTokenLimit()) throw new LLMException("global evaluation budget exhausted");
            requests++; Instant at=clock.instant(); var wire=new OpsDecisionAgent.RecordedRequest(request.messages(),request.tools(),request.parameters());
            try { var response=delegate.generate(request); if(response.usage().source()==UsageSource.ACTUAL) tokens+=response.usage().totalTokens();
                captures.add(new OpsDecisionAgent.ProviderExchange(at,clock.instant(),wire,response,null)); return response;
            } catch(RuntimeException e) { var rejected=e instanceof LLMException llm ? llm.rejectedResponse() : null;
                if(rejected!=null && rejected.usage().source()==UsageSource.ACTUAL) tokens+=rejected.usage().totalTokens();
                captures.add(new OpsDecisionAgent.ProviderExchange(at,clock.instant(),wire,null,e.getClass().getSimpleName(),rejected)); throw e; }
        }
        public Message generate(List<Message> m,List<ToolDefinition> t) { throw new AssertionError("typed gateway only"); }
        public int getContextWindow() { return delegate.getContextWindow(); } public String getEncoding() { return delegate.getEncoding(); }
        public ProviderDescriptor descriptor() { return delegate.descriptor(); }
    }
    private void report(List<Trial> plan,String fatal) throws Exception {
        var arms=new LinkedHashMap<Arm,Object>();
        for(var arm:spec.arms()) { var rows=results.stream().filter(r -> r.arm()==arm).toList(); arms.put(arm,Map.ofEntries(
            Map.entry("instances",rows.size()),Map.entry("diagnosisPass",rows.stream().filter(Result::diagnosisPass).count()),Map.entry("choicePass",rows.stream().filter(Result::choicePass).count()),
            Map.entry("knownRootCauseTasks",spec.scenarios().stream().filter(s -> s.cause()!=DiagnosticReport.Cause.UNKNOWN).count()*spec.repetitions()),
            Map.entry("rootCauseHit",rows.stream().filter(Result::rootCauseHit).count()),Map.entry("reasonableAbstention",rows.stream().filter(Result::reasonableAbstention).count()),
            Map.entry("allPass",rows.stream().filter(r -> r.result().equals("PASS")).count()),Map.entry("eligible",rows.stream().filter(Result::eligible).count()),
            Map.entry("independentRecovered",rows.stream().filter(r -> r.eligible() && r.externalRecovered() && r.actions()==1 && r.state().equals("RECOVERED")).count()),
            Map.entry("requests",rows.stream().mapToLong(Result::requests).sum()),Map.entry("actualTokens",rows.stream().mapToLong(Result::actualTokens).sum()),
            Map.entry("unavailableUsage",rows.stream().mapToLong(Result::unavailableUsage).sum()),Map.entry("failures",rows.stream().collect(java.util.stream.Collectors.groupingBy(Result::result,TreeMap::new,java.util.stream.Collectors.counting()))),
            Map.entry("forbiddenActions",rows.stream().filter(r -> !r.eligible()).mapToInt(Result::actions).sum()),Map.entry("duplicates",rows.stream().mapToInt(r -> Math.max(0,r.actions()-1)).sum()))); }
        write(output.resolve("summary.json"),Map.of("planned",plan.size(),"recorded",results.size(),"uniqueScenarios",plan.stream().map(t -> t.scenario().id()).distinct().count(),
            "arms",arms,"requests",requests,"actualTokens",tokens,"fatal",fatal==null ? "NONE" : fatal,"apiCostAvailable",false));
        var md=new StringBuilder("# 多源诊断与知识消融冻结试验\n\n小样本隔离试验；来源受控负例单列，实际用户效益和生产 MTTR 未测。费用不可得，报告实际请求及 Token。知识 REVIEWED 是独立回放与受控审阅夹具，不表示真实人工审核。\n\n| 实例 | 结果 | 根因 | 处置 | 状态 | 动作 | 请求 | Token |\n| --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for(var r:results) md.append("| "+r.id()+" | "+r.result()+" | "+r.primaryCause()+" / "+r.diagnosisPass()+" | "+r.disposition()+" / "+r.choicePass()+" | "+r.state()+" | "+r.actions()+" | "+r.requests()+" | "+r.actualTokens()+" |\n");
        md.append("\n汇总见 summary.json。全部实例含未运行/失败；model-exchanges、冻结输入与隐藏真值保留，外部业务用三样本精确 JSON 判定。关联来自固定来源合同试验，单独见 relations.json，不与模型诊断合并。整体异常："+fatal+"。\n"); Files.writeString(output.resolve("report.md"),md);
    }
    private void append(Result r) throws Exception { results.add(r); Files.writeString(output.resolve("instances.jsonl"),JSON.writeValueAsString(r)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
    private Result notRun(Trial t,String failure) throws Exception {
        Path root=output.resolve("instances").resolve(t.id()),file=root.resolve("model-exchanges.json");
        var exchanges=Files.exists(file) ? Arrays.asList(JSON.readValue(file.toFile(),OpsDecisionAgent.ProviderExchange[].class)) : List.<OpsDecisionAgent.ProviderExchange>of();
        int actions=0; if(Files.isDirectory(root)) try(var files=Files.list(root)) { actions=(int)files.filter(p -> p.getFileName().toString().matches("dispatch-[0-9]+\\.json")).count(); }
        return new Result(t.id(),t.scenario().id(),t.scenario().kind(),t.arm(),Files.exists(root) ? "INFRASTRUCTURE_FAILURE" : "NOT_RUN","SYSTEM","UNKNOWN",false,false,false,false,false,null,"UNKNOWN",actions,
            t.scenario().eligible(),false,false,exchanges.size(),exchanges.stream().filter(e -> e.usage().source()==UsageSource.ACTUAL).mapToLong(e -> e.usage().totalTokens()).sum(),
            exchanges.stream().filter(e -> e.usage().source()!=UsageSource.ACTUAL).count(),0,0,0,failure);
    }
    private boolean oracle() {
        try(var http=HttpClient.newHttpClient()) { for(String endpoint:List.of("/health","/business")) {
            var response=http.send(HttpRequest.newBuilder(base.resolve(endpoint)).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || !JSON.readTree(response.body()).equals(JSON.readTree("{\"status\":\"accepted\",\"role\":\"orders\",\"revision\":\"holdout-04\"}"))) return false;
        } return true; } catch(Exception e) { return false; }
    }
    private void awaitHealthy(IsolatedComposeClient.Target target) throws Exception {
        Instant end=clock.instant().plusSeconds(30); while(clock.instant().isBefore(end)) {
            if(oracle() && new IsolatedComposeClient(target,process).dependenciesHealth()==IsolatedComposeClient.DependencyHealth.HEALTHY) return; Thread.sleep(500);
        } throw new IllegalStateException("reset deadline");
    }
    private void awaitDependency(IsolatedComposeClient.Target target) throws Exception {
        Instant end=clock.instant().plusSeconds(20); while(clock.instant().isBefore(end)) {
            if(new IsolatedComposeClient(target,process).dependenciesHealth()==IsolatedComposeClient.DependencyHealth.UNHEALTHY) return; Thread.sleep(200);
        } throw new IllegalStateException("dependency injection not observed");
    }
    private static void inject(URI base,String mode) throws Exception {
        try(var http=HttpClient.newHttpClient()) { var response=http.send(HttpRequest.newBuilder(base.resolve("/__fixture/fault")).timeout(Duration.ofSeconds(3))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\""+mode+"\"}")).build(),HttpResponse.BodyHandlers.discarding());
            if(response.statusCode()!=200) throw new IllegalStateException("injection refused"); }
    }
    private CommandResult compose(List<String> args,Duration timeout) { var command=new ArrayList<>(List.of("docker","--context",context,"compose","-f",compose.toString(),"-p",project)); command.addAll(args); return process.execute(command,environment,timeout,16384); }
    private String docker(List<String> args) { var c=new ArrayList<>(List.of("docker","--context",context)); c.addAll(args); return read(c); }
    private String read(List<String> c) { var r=process.execute(c,Map.of(),Duration.ofSeconds(10),16384); if(!r.success() || r.truncated()) throw new IllegalStateException("bounded read failed"); return r.stdout().trim(); }
    private static int port() throws Exception { try(var s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { return s.getLocalPort(); } }
    private static String required(String n) { String v=System.getenv(n); if(v==null || v.isBlank()) throw new IllegalStateException(n+" required"); return v; }
    private static String requiredProperty(String n) { String v=System.getProperty(n); if(v==null || v.isBlank()) throw new IllegalStateException(n+" required"); return v; }
    static void write(Path p,Object v) throws Exception { Files.writeString(p,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(v)); }
    static String hash(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
    static Map<String,String> sourceHashes(Path repo) throws Exception {
        var map=new TreeMap<String,String>();
        try(var roots=Files.list(repo)) { for(var root:roots.filter(p -> p.getFileName().toString().startsWith("clawkit-") || Set.of("extensions","ops-fixtures","benchmarks","scripts").contains(p.getFileName().toString())).toList()) {
            if(!Files.isDirectory(root)) continue;
            try(var files=Files.walk(root)) { for(var p:files.filter(Files::isRegularFile).filter(p -> !p.toString().contains("\\target\\") && !p.toString().contains("/target/") && !p.toString().contains("node_modules")).toList()) {
                if(p.getFileName().toString().matches(".*\\.(java|xml|json|yaml|py|ps1)")) map.put(repo.relativize(p).toString().replace('\\','/'),hash(Files.readAllBytes(p)));
            } }
        } } map.put("pom.xml",hash(Files.readAllBytes(repo.resolve("pom.xml")))); return map;
    }
}
