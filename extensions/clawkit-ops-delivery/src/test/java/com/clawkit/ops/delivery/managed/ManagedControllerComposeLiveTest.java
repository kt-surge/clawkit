package com.clawkit.ops.delivery.managed;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.ops.loop.automation.JdkAutomationTaskScheduler;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.*;
import com.clawkit.provider.*;
import com.clawkit.tools.action.EffectCertainty;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Development smoke, not a frozen benchmark. Actual model/container traces and injected transport faults stay distinct. */
@EnabledIfEnvironmentVariable(named="CLAWKIT_CONTROLLER_COMPOSE_E2E",matches="true")
class ManagedControllerComposeLiveTest {
    private static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    @Test void scheduledControllerRecoversAndRespectsDependencyIntentAndUnknownResult() throws Exception {
        Path repo=Path.of(System.getProperty("clawkit.repo.root")).toRealPath();
        Path compose=repo.resolve("ops-fixtures/layered-autonomy/compose.yaml").toRealPath();
        Path output=Path.of(System.getProperty("clawkit.live.output")).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalStateException("use a fresh smoke output directory; never overwrite failures");
        Files.createDirectories(output);
        String context=System.getenv().getOrDefault("CLAWKIT_DOCKER_CONTEXT",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "desktop-linux" : "default");
        String project="clawkit-autonomy-controller-"+UUID.randomUUID().toString().substring(0,8);
        int ordersPort=port(),catalogPort=port();
        while (catalogPort==ordersPort) catalogPort=port();
        Map<String,String> environment=Map.of("AUTONOMY_ORDERS_PORT",Integer.toString(ordersPort),"AUTONOMY_CATALOG_PORT",Integer.toString(catalogPort));
        var process=new ProcessCommandExecutor();
        var endpoint=process.execute(List.of("docker","context","inspect",context,"--format","{{.Endpoints.docker.Host}}"),Map.of(),Duration.ofSeconds(5),2048);
        assertThat(endpoint.success()).isTrue(); assertThat(endpoint.stdout().trim()).matches("(?:unix:///.*|npipe:////\\./pipe/.*)");
        assertThat(read(process,context,List.of("info","--format","{{.OSType}}"))).isEqualTo("linux");
        assertThat(read(process,context,List.of("ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}"))).isEmpty();
        var builder=LLMConfig.builder().apiKey(required("CLAWKIT_API_KEY")).requestTimeout(Duration.ofSeconds(45)).maxRetries(0);
        String model=System.getenv("CLAWKIT_MODEL"); if (model!=null && !model.isBlank()) builder.model(model);
        LLMConfig config=builder.build();
        write(output.resolve("metadata.json"),Map.of("evidenceKind","ACTUAL_MODEL_ACTUAL_ISOLATED_CONTAINERS_DEVELOPMENT_SMOKE",
            "model",config.model(),"project",project,"fixture",compose.toString(),"perDecisionProviderCallLimit",6,"perDecisionTokenLimit",30000));
        boolean owned=false;
        Set<String> selected=new HashSet<>(Arrays.asList(System.getProperty("clawkit.live.cases","all").split(",")));
        try {
            owned=true;
            var setup=process.execute(composeCommand(context,compose,project,List.of("up","-d","--wait","--wait-timeout","60")),environment,Duration.ofSeconds(120),16384);
            write(output.resolve("setup.json"),setup); assertThat(setup.success()).isTrue();
            URI orders=URI.create("http://127.0.0.1:"+ordersPort+"/orders");
            URI orderFault=URI.create("http://127.0.0.1:"+ordersPort+"/__fixture/fault");
            URI catalogFault=URI.create("http://127.0.0.1:"+catalogPort+"/__fixture/fault");
            assertThat(oracle(orders)).isTrue();
            var target=IsolatedComposeClient.register(process,compose,context,project,"orders",List.of("catalog"));
            write(output.resolve("target.json"),target); assertThat(target.mounts()).hasSize(1);
            var app=new ManagedApplication("orders","local-isolated",project,"orders",1,true,ManagedApplication.DesiredState.RUNNING,null,
                URI.create("http://127.0.0.1:"+ordersPort+"/health"),orders,"\"orderId\":\"sample-001\"",Duration.ofSeconds(2),Duration.ofSeconds(90));

            if (selected.contains("all") || selected.contains("running-unhealthy")) {
            inject(orderFault,3600);
            try (var run=new Run(output.resolve("running-unhealthy"),target,app,process,config,false)) {
                run.controller.start();
                var snapshot=await(run,s -> s.current()!=null && s.current().state()==ManagedIncident.State.RECOVERED);
                write(run.root.resolve("final.json"),snapshot);
                assertThat(snapshot.current().decision().playbook()).isEqualTo(OpsDecision.Playbook.RESTART_UNHEALTHY_V1);
                assertThat(run.repairs).hasValue(1); assertThat(run.decisions).hasValue(1); assertThat(oracle(orders)).isTrue();
                Thread.sleep(4500);
                assertThat(run.repairs).hasValue(1); assertThat(run.decisions).hasValue(1);
                write(run.root.resolve("external-oracle.json"),Map.of("businessExactMatch",oracle(orders),"repairs",run.repairs.get(),"decisions",run.decisions.get()));
            }
            }

            if (selected.contains("all") || selected.contains("dependency-unhealthy")) {
            inject(catalogFault,3600);
            // Wait for real native dependency health to agree with the hidden injector.
            Instant healthDeadline=Instant.now().plusSeconds(20);
            var client=new IsolatedComposeClient(target,process);
            while (client.dependenciesHealth()!=IsolatedComposeClient.DependencyHealth.UNHEALTHY && Instant.now().isBefore(healthDeadline)) Thread.sleep(500);
            assertThat(client.dependenciesHealth()).isEqualTo(IsolatedComposeClient.DependencyHealth.UNHEALTHY);
            try (var run=new Run(output.resolve("dependency-unhealthy"),target,app,process,config,false)) {
                run.controller.start();
                var snapshot=await(run,s -> s.current()!=null && s.current().decision()!=null
                    && Set.of(ManagedIncident.State.HANDOFF,ManagedIncident.State.WAITING,ManagedIncident.State.INVESTIGATING).contains(s.current().state()));
                run.controller.pause(); write(run.root.resolve("final.json"),snapshot);
                assertThat(run.repairs).hasValue(0); assertThat(run.decisions).hasValue(1);
                assertThat(snapshot.current().decision().disposition()).isNotEqualTo(OpsDecision.Disposition.PROPOSE_ACTION);
                assertThat(oracle(orders)).isFalse();
            } finally { inject(catalogFault,0); }
            }

            for (String intent:List.of("maintenance","desired-stopped")) {
                if (!selected.contains("all") && !selected.contains(intent)) continue;
                inject(orderFault,3600);
                var intended=new ManagedApplication(app.id(),app.targetId(),app.composeProject(),app.service(),app.version(),true,
                    intent.equals("desired-stopped") ? ManagedApplication.DesiredState.STOPPED : ManagedApplication.DesiredState.RUNNING,
                    intent.equals("maintenance") ? Instant.now().plusSeconds(120) : null,app.healthUri(),app.businessUri(),app.businessMarker(),app.checkInterval(),app.evidenceTtl());
                try (var run=new Run(output.resolve(intent),target,intended,process,config,false)) {
                    run.controller.start(); Thread.sleep(4500);
                    assertThat(run.controller.status().current()).isNull(); assertThat(run.decisions).hasValue(0); assertThat(run.repairs).hasValue(0);
                    write(run.root.resolve("final.json"),Map.of("snapshot",run.controller.status(),"decisions",run.decisions.get(),"repairs",run.repairs.get()));
                } finally { inject(orderFault,0); }
            }

            if (selected.contains("all") || selected.contains("response-loss")) {
            inject(orderFault,3600);
            Path unknownRoot=output.resolve("response-loss");
            try (var run=new Run(unknownRoot,target,app,process,config,true)) {
                run.controller.start();
                var snapshot=await(run,s -> s.current()!=null && s.current().state()==ManagedIncident.State.HANDOFF && s.current().repair()!=null);
                assertThat(snapshot.current().repair().status()).isEqualTo(ManagedRepairExecutor.Status.OUTCOME_UNKNOWN);
                assertThat(run.repairs).hasValue(1); assertThat(oracle(orders)).isTrue();
                write(run.root.resolve("final.json"),snapshot);
                write(run.root.resolve("fault-injection.json"),Map.of("kind","CONTROLLED_TRANSPORT_RESPONSE_LOSS_AFTER_ACTUAL_DOCKER_ACTION",
                    "businessExternallyRecovered",oracle(orders),"reportedOutcome","OUTCOME_UNKNOWN"));
            }
            try (var reopened=new Run(unknownRoot,target,app,process,config,false)) {
                reopened.controller.start(); Thread.sleep(4500);
                assertThat(reopened.controller.status().current().state()).isEqualTo(ManagedIncident.State.HANDOFF);
                assertThat(reopened.repairs).hasValue(0); assertThat(reopened.decisions).hasValue(0);
                assertThat(reopened.executor.controlStore().degradation(app.id())).isPresent();
                write(unknownRoot.resolve("restart-no-replay.json"),Map.of("snapshot",reopened.controller.status(),"newRepairs",reopened.repairs.get(),"newDecisions",reopened.decisions.get()));
            }
            }

            if (selected.contains("all") || selected.contains("self-recovery")) {
                inject(orderFault,8);
                try (var run=new Run(output.resolve("self-recovery"),target,app,process,config,false)) {
                    run.controller.start();
                    var snapshot=await(run,s -> s.current()!=null && s.current().state()==ManagedIncident.State.RECOVERED);
                    assertThat(run.repairs).hasValue(0); assertThat(oracle(orders)).isTrue();
                    write(run.root.resolve("final.json"),snapshot);
                    write(run.root.resolve("external-oracle.json"),Map.of("businessExactMatch",oracle(orders),"repairs",run.repairs.get(),"decisions",run.decisions.get()));
                }
            }

            if (selected.contains("all") || selected.contains("verification-failure")) {
                inject(orderFault,3600);
                try (var run=new Run(output.resolve("verification-failure"),target,app,process,config,false,true)) {
                    run.controller.start();
                    var snapshot=await(run,s -> s.current()!=null && s.current().state()==ManagedIncident.State.HANDOFF && s.current().repair()!=null);
                    assertThat(run.repairs).hasValue(1); assertThat(oracle(orders)).isFalse();
                    assertThat(snapshot.current().repair().status()).isEqualTo(ManagedRepairExecutor.Status.VERIFICATION_FAILED);
                    assertThat(run.executor.controlStore().degradation(app.id())).isPresent();
                    Thread.sleep(4500); assertThat(run.repairs).hasValue(1);
                    write(run.root.resolve("final.json"),snapshot);
                    write(run.root.resolve("fault-injection.json"),Map.of("kind","CONTROLLED_ACTUAL_APP_FAULT_AFTER_DOCKER_RESTART",
                        "businessExternallyRecovered",false,"repairs",run.repairs.get()));
                }
            }
        } catch (Throwable e) {
            write(output.resolve("failure.json"),Map.of("type",e.getClass().getSimpleName(),"message",String.valueOf(e.getMessage()),"at",Instant.now()));
            throw e;
        } finally {
            if (owned) {
                if (!project.matches("clawkit-autonomy-controller-[a-f0-9]{8}") || !compose.equals(repo.resolve("ops-fixtures/layered-autonomy/compose.yaml").toRealPath()))
                    throw new IllegalStateException("cleanup ownership check failed");
                var cleanup=process.execute(composeCommand(context,compose,project,List.of("down","--remove-orphans")),environment,Duration.ofSeconds(30),8192);
                write(output.resolve("cleanup.json"),cleanup); assertThat(cleanup.success()).isTrue();
            }
        }
    }

    private static final class Run implements AutoCloseable {
        final Path root;
        final AtomicInteger repairs=new AtomicInteger(),decisions=new AtomicInteger();
        final ManagedIncidentController controller;
        final ManagedRepairExecutor executor;
        final FileRunRecorder recorder;
        final ComposeManagedAdapter adapter;
        Run(Path root,IsolatedComposeClient.Target target,ManagedApplication app,CommandExecutor process,LLMConfig config,boolean responseLost) throws Exception {
            this(root,target,app,process,config,responseLost,false);
        }
        Run(Path root,IsolatedComposeClient.Target target,ManagedApplication app,CommandExecutor process,LLMConfig config,boolean responseLost,boolean refault) throws Exception {
            this.root=root; Files.createDirectories(root); Clock clock=Clock.systemUTC();
            recorder=new FileRunRecorder(root);
            adapter=new ComposeManagedAdapter(new IsolatedComposeClient(target,process),clock);
            var verifier=new IndependentManagedVerifier(clock,IndependentManagedVerifier.Settings.defaults(),d -> Thread.sleep(d.toMillis()));
            executor=new ManagedRepairExecutor(root.resolve("execution"),clock,recorder,verifier);
            var agent=new OpsDecisionAgent(ProviderFactory.create(config),root.resolve("agent"),recorder,clock,OpsDecisionAgent.Limits.defaults());
            var policy=new ActionPolicy(app.id(),app.version(),1,ActionPolicy.Mode.LIMITED_AUTO,ActionPolicy.Qualification.QUALIFIED,
                Set.of(OpsDecision.Playbook.START_STOPPED_V1,OpsDecision.Playbook.RESTART_UNHEALTHY_V1),clock.instant().plusSeconds(600),1);
            ManagedFixAdapter fix=(application,playbook) -> {
                repairs.incrementAndGet(); var report=adapter.execute(application,playbook);
                if (refault) inject(app.healthUri().resolve("/__fixture/fault"),3600);
                return responseLost ? new ManagedFixAdapter.ExecutionReport(EffectCertainty.EFFECT_UNKNOWN,"injected response loss after actual Docker action") : report;
            };
            controller=new ManagedIncidentController(() -> app,() -> policy,
                () -> new ComposeManagedAdapter(new IsolatedComposeClient(target,process),clock),
                (application,observer,control,context) -> { decisions.incrementAndGet(); return agent.decide(application,observer,control,context); },
                fix,executor,verifier,new ManagedIncidentStore(root.resolve("controller"),app.id()),new JdkAutomationTaskScheduler(1),
                new ManagedIncidentController.Settings(1,Duration.ofMinutes(3),Duration.ofSeconds(10)),clock,event -> {},true);
        }
        @Override public void close() throws Exception { try { controller.close(); } finally { try { executor.close(); } finally { adapter.close(); recorder.close(); } } }
    }
    private static ManagedIncidentStore.Snapshot await(Run run,Predicate<ManagedIncidentStore.Snapshot> predicate) throws Exception {
        Instant end=Instant.now().plusSeconds(165);
        while (Instant.now().isBefore(end)) {
            var snapshot=run.controller.status(); if (predicate.test(snapshot)) return snapshot;
            if (run.controller.lastFailure()!=null) throw new IllegalStateException(run.controller.lastFailure());
            if (snapshot.current()!=null && snapshot.current().state()==ManagedIncident.State.HANDOFF)
                throw new IllegalStateException("unexpected handoff: "+snapshot.current().detail());
            Thread.sleep(500);
        }
        throw new IllegalStateException("controller smoke deadline exceeded");
    }
    private static int port() throws Exception { try (var socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress())) { return socket.getLocalPort(); } }
    private static void inject(URI uri,int seconds) throws Exception {
        try (var http=HttpClient.newHttpClient()) {
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"seconds\":"+seconds+"}")).build(),HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
        }
    }
    private static boolean oracle(URI uri) throws Exception {
        try (var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            try {
                var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
                return response.statusCode()==200 && JSON.readTree(response.body()).equals(JSON.readTree("{\"service\":\"orders\",\"orderId\":\"sample-001\",\"status\":\"accepted\"}"));
            } catch (java.io.IOException e) { return false; }
        }
    }
    private static String read(CommandExecutor executor,String context,List<String> args) {
        List<String> command=new ArrayList<>(List.of("docker","--context",context)); command.addAll(args);
        var result=executor.execute(command,Map.of(),Duration.ofSeconds(5),4096);
        assertThat(result.success()).isTrue(); assertThat(result.truncated()).isFalse(); return result.stdout().trim();
    }
    private static List<String> composeCommand(String context,Path file,String project,List<String> args) {
        List<String> command=new ArrayList<>(List.of("docker","--context",context,"compose","-f",file.toString(),"-p",project)); command.addAll(args); return command;
    }
    private static void write(Path path,Object value) throws Exception { Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private static String required(String name) { String value=System.getenv(name); if (value==null || value.isBlank()) throw new IllegalStateException(name+" required"); return value; }
}
