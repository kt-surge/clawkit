package com.clawkit.ops.delivery.managed;

import com.clawkit.observability.FileRunRecorder;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.*;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.ProviderFactory;
import com.clawkit.tools.control.ExecutionControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Explicitly enabled actual Linux containers + actual model; safe setup/cleanup only for this newly created project. */
@EnabledIfEnvironmentVariable(named="CLAWKIT_COMPOSE_E2E",matches="true")
class ManagedComposeLiveTest {
    private static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    @Test void actualModelRestoresIsolatedExitedContainerAndExternalBusinessOraclePasses() throws Exception {
        Path repo=Path.of(System.getProperty("clawkit.repo.root")).toRealPath();
        Path compose=repo.resolve("ops-fixtures/app-down/compose.yaml").toRealPath();
        Path output=Path.of(System.getProperty("clawkit.live.output")).toAbsolutePath().normalize();
        Files.createDirectories(output);
        String context=System.getenv().getOrDefault("CLAWKIT_DOCKER_CONTEXT",System.getProperty("os.name").toLowerCase().contains("win") ? "desktop-linux" : "default");
        String project="clawkit-autonomy-p2-"+UUID.randomUUID().toString().substring(0,8);
        int port;
        try (var socket=new ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())) { port=socket.getLocalPort(); }
        var process=new ProcessCommandExecutor();
        // Verify bootstrap is local before creating any containers.
        var endpoint=process.execute(List.of("docker","context","inspect",context,"--format","{{.Endpoints.docker.Host}}"),Map.of(),Duration.ofSeconds(5),2048);
        assertThat(endpoint.success()).isTrue();
        assertThat(endpoint.stdout().trim()).matches("(?:unix:///.*|npipe:////\\./pipe/.*)");
        var writes=new AtomicInteger();
        CommandExecutor tracked=(command,environment,timeout,limit) -> {
            if (command.size()>3 && Set.of("start","restart").contains(command.get(3))) writes.incrementAndGet();
            return process.execute(command,environment,timeout,limit);
        };
        Clock clock=Clock.systemUTC();
        ManagedApplication app=new ManagedApplication("demo","local-isolated",project,"demo-api",1,true,
            ManagedApplication.DesiredState.RUNNING,null,URI.create("http://127.0.0.1:"+port+"/health"),
            URI.create("http://127.0.0.1:"+port+"/"),"demo-api",Duration.ofSeconds(5),Duration.ofSeconds(90));
        var builder=LLMConfig.builder().apiKey(required("CLAWKIT_API_KEY")).requestTimeout(Duration.ofSeconds(45)).maxRetries(0);
        String model=System.getenv("CLAWKIT_MODEL"); if (model!=null && !model.isBlank()) builder.model(model);
        var config=builder.build();
        Files.writeString(output.resolve("metadata.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
            "evidenceKind","ACTUAL_MODEL_ACTUAL_ISOLATED_CONTAINERS","model",config.model(),"scenario","exited-stateless-compose-service",
            "application",app,"project",project,"providerCallLimit",6,"tokenLimit",30000,"fixture",compose.toString())));
        boolean created=false;
        try {
            // The UUID project is newly owned by this test; the supplied compose path is fixed inside the repository.
            created=true;
            var up=process.execute(composeCommand(context,compose,project,List.of("up","-d","--wait","--wait-timeout","45")),
                Map.of("OPS_HTTP_PORT",Integer.toString(port)),Duration.ofSeconds(60),16384);
            Files.writeString(output.resolve("setup.json"),JSON.writeValueAsString(up));
            assertThat(up.success()).isTrue();
            assertThat(externalOracle(app.businessUri())).isTrue();
            // Gateway consumes demo-api; it is not an upstream dependency of demo-api.
            var target=IsolatedComposeClient.register(tracked,compose,context,project,"demo-api",List.of());
            Files.writeString(output.resolve("target.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(target));
            String gatewayId=IsolatedComposeClient.register(tracked,compose,context,project,"gateway",List.of()).containerId();
            var stop=process.execute(List.of("docker","--context",context,"stop","--time","2",target.containerId()),Map.of(),Duration.ofSeconds(10),4096);
            assertThat(stop.success()).isTrue();
            assertThat(externalOracle(app.businessUri())).isFalse();
            try (var recorder=new FileRunRecorder(output);
                 var modelObserver=new ComposeManagedAdapter(new IsolatedComposeClient(target,tracked),clock);
                 var fix=new ComposeManagedAdapter(new IsolatedComposeClient(target,tracked),clock);
                 var executor=new ManagedRepairExecutor(output.resolve("state"),clock,recorder,
                     new IndependentManagedVerifier(clock,IndependentManagedVerifier.Settings.defaults(),d -> Thread.sleep(d.toMillis())))) {
                executor.recover(app);
                var agent=new OpsDecisionAgent(ProviderFactory.create(config),output.resolve("agent"),recorder,clock,OpsDecisionAgent.Limits.defaults());
                var decision=agent.decide(app,modelObserver);
                Files.writeString(output.resolve("decision.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(decision));
                assertThat(decision.origin()).isEqualTo(OpsDecisionAgent.Origin.MODEL);
                assertThat(decision.decision().disposition()).isEqualTo(OpsDecision.Disposition.PROPOSE_ACTION);
                var policy=new ActionPolicy(app.id(),app.version(),1,ActionPolicy.Mode.LIMITED_AUTO,ActionPolicy.Qualification.QUALIFIED,
                    Set.of(OpsDecision.Playbook.START_STOPPED_V1),clock.instant().plusSeconds(300),1);
                var result=executor.execute("inc-live-start",app,decision.decision(),decision.evidence(),RepairAuthorization.Automatic.INSTANCE,
                    () -> app,() -> policy,() -> new ComposeManagedAdapter(new IsolatedComposeClient(target,tracked),clock),fix,ExecutionControl.none());
                Files.writeString(output.resolve("repair.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.RECOVERED);
                assertThat(result.verification().samples()).hasSizeGreaterThanOrEqualTo(3);
                boolean oracle=externalOracle(app.businessUri());
                Files.writeString(output.resolve("oracle.json"),JSON.writeValueAsString(Map.of("businessJsonExactMatch",oracle,"repairTransportCalls",writes.get(),"gatewayContainerId",gatewayId)));
                assertThat(oracle).isTrue(); assertThat(writes).hasValue(1);
                var repeated=executor.execute("inc-live-start",app,decision.decision(),decision.evidence(),RepairAuthorization.Automatic.INSTANCE,
                    () -> app,() -> policy,() -> new ComposeManagedAdapter(new IsolatedComposeClient(target,tracked),clock),fix,ExecutionControl.none());
                assertThat(repeated.status()).isEqualTo(ManagedRepairExecutor.Status.BLOCKED); assertThat(writes).hasValue(1);
            }
        } finally {
            if (created) {
                if (!project.matches("clawkit-autonomy-p2-[a-f0-9]{8}") || !compose.equals(repo.resolve("ops-fixtures/app-down/compose.yaml").toRealPath()))
                    throw new IllegalStateException("cleanup ownership check failed");
                var down=process.execute(composeCommand(context,compose,project,List.of("down","--remove-orphans")),
                    Map.of("OPS_HTTP_PORT",Integer.toString(port)),Duration.ofSeconds(30),8192);
                Files.writeString(output.resolve("cleanup.json"),JSON.writeValueAsString(down));
                assertThat(down.success()).isTrue();
            }
        }
    }
    private static List<String> composeCommand(String context,Path file,String project,List<String> args) {
        List<String> command=new ArrayList<>(List.of("docker","--context",context,"compose","-f",file.toString(),"-p",project)); command.addAll(args); return command;
    }
    private static boolean externalOracle(URI uri) throws Exception {
        try (var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            try {
                var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
                return response.statusCode()==200 && "demo-api".equals(JSON.readTree(response.body()).path("message").asText());
            } catch (java.io.IOException e) { return false; }
        }
    }
    private static String required(String name) {
        String value=System.getenv(name); if (value==null || value.isBlank()) throw new IllegalStateException(name+" required"); return value;
    }
}
