package com.clawkit.ops.delivery.managed;

import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.*;
import com.clawkit.provider.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Paid development smoke, separate from frozen evaluation. Actual Docker OOM, logs and HTTP; hidden injection stays external. */
@EnabledIfEnvironmentVariable(named="CLAWKIT_DIAGNOSTIC_COMPOSE_E2E",matches="true")
class DiagnosticComposeLiveTest {
    static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
    record Result(String scenario,boolean passed,String origin,String failureType,String cause,String disposition,long requests,long actualTokens,String error) {}

    @Test void actualModelInvestigatesFourIsolatedChainsThroughProductService() throws Exception {
        Path repo=Path.of(System.getProperty("clawkit.repo.root")).toRealPath();
        Path compose=repo.resolve("ops-fixtures/diagnostic-autonomy/compose.yaml").toRealPath();
        Path output=Path.of(System.getProperty("clawkit.live.output")).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalStateException("fresh output directory required"); Files.createDirectories(output);
        String context=System.getenv().getOrDefault("CLAWKIT_DOCKER_CONTEXT","desktop-linux");
        String project="clawkit-autonomy-diagnostic-"+UUID.randomUUID().toString().substring(0,8);
        int apiPort=port(),depPort=port(); while (depPort==apiPort) depPort=port();
        var environment=Map.of("DIAGNOSTIC_API_PORT",""+apiPort,"DIAGNOSTIC_DEPENDENCY_PORT",""+depPort);
        var process=new ProcessCommandExecutor(); var requests=new AtomicLong(); var tokens=new AtomicLong();
        URI base=URI.create("http://127.0.0.1:"+apiPort),dependency=URI.create("http://127.0.0.1:"+depPort);
        var config=LLMConfig.builder().apiKey(required("CLAWKIT_API_KEY")).requestTimeout(Duration.ofSeconds(45)).maxRetries(0).build();
        var selected=Arrays.asList(System.getProperty("clawkit.live.cases","dependency,configuration,historical,oom").split(","));
        if (selected.isEmpty() || new HashSet<>(selected).size()!=selected.size() || !Set.of("dependency","configuration","historical","oom").containsAll(selected))
            throw new IllegalArgumentException("bounded explicit smoke scenarios required");
        long maxRequests=selected.size()*6L,maxTokens=selected.size()*30000L;
        var results=new ArrayList<Result>(); boolean owned=false;
        write(output.resolve("metadata.json"),Map.of("evidenceKind","ACTUAL_MODEL_ACTUAL_CONTAINERS_DEVELOPMENT_SMOKE",
            "model",config.model(),"project",project,"maxRequests",maxRequests,"globalTokenBudget",maxTokens,"perDecisionTokenBudget",30000,"fixture",compose.toString(),"scenarios",selected));
        try {
            String endpoint=read(process,List.of("docker","context","inspect",context,"--format","{{.Endpoints.docker.Host}}"));
            assertThat(endpoint).matches("(?:unix:///.*|npipe:////\\./pipe/.*)");
            assertThat(read(process,List.of("docker","--context",context,"info","--format","{{.OSType}}"))).isEqualTo("linux");
            assertThat(read(process,List.of("docker","--context",context,"ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}"))).isEmpty();
            owned=true;
            var setup=process.execute(command(context,compose,project,List.of("up","-d","--wait","--wait-timeout","60")),environment,Duration.ofSeconds(120),16384);
            write(output.resolve("setup.json"),setup); assertThat(setup.success()).isTrue();
            for (String scenario:selected) {
                if (requests.get()+6>maxRequests || tokens.get()+30000>maxTokens) {
                    results.add(new Result(scenario,false,"NOT_RUN","GLOBAL_BUDGET_STOP",null,null,0,0,null)); continue;
                }
                Path caseRoot=output.resolve(scenario); Files.createDirectories(caseRoot);
                var exchanges=new ArrayList<OpsDecisionAgent.ProviderExchange>();
                long beforeRequests=requests.get(),beforeTokens=tokens.get();
                var provider=new RecordingProvider(ProviderFactory.create(config),exchanges,requests,tokens,maxRequests,maxTokens);
                try {
                    inject(dependency,"healthy"); inject(base,"healthy");
                    var service=new ManagedOperationsService(caseRoot.resolve("state"),process,Clock.systemUTC());
                    service.register(new ManagedOperationsService.RegistrationRequest("api",compose,context,project,"api",List.of("dependency"),true,
                        base.resolve("/health"),base.resolve("/business"),"\"status\":\"accepted\"",Duration.ofSeconds(5)));
                    if (scenario.equals("dependency")) {
                        inject(dependency,"application");
                        var target=IsolatedComposeClient.register(process,compose,context,project,"api",List.of("dependency"));
                        Instant until=Instant.now().plusSeconds(15);
                        while (new IsolatedComposeClient(target,process).dependenciesHealth()!=IsolatedComposeClient.DependencyHealth.UNHEALTHY && Instant.now().isBefore(until)) Thread.sleep(200);
                    } else if (scenario.equals("configuration")) {
                        inject(base,"configuration");
                        Path input=caseRoot.resolve("change.json"); write(input,new EvidenceEnvelope.ChangeRecord("release-v2","api",1,project,"api",Instant.now(),
                            "v2","Expected configuration schema changed to v2; runtime schema still v1 (isolated fault simulation)","development-harness"));
                        service.importChange("api",input,"development-harness");
                    } else if (scenario.equals("historical")) { inject(base,"application"); inject(base,"healthy"); }
                    else {
                        inject(base,"oom");
                        var target=IsolatedComposeClient.register(process,compose,context,project,"api",List.of("dependency"));
                        Instant until=Instant.now().plusSeconds(15);
                        while (!new IsolatedComposeClient(target,process).resources().oomKilled() && Instant.now().isBefore(until)) Thread.sleep(200);
                        assertThat(new IsolatedComposeClient(target,process).resources().oomKilled()).isTrue();
                    }
                    var view=service.diagnose("api",provider); write(caseRoot.resolve("view.json"),view);
                    String expected=switch(scenario) { case "dependency" -> "DEPENDENCY_FAILURE"; case "configuration" -> "CONFIGURATION_MISMATCH";
                        case "oom" -> "RESOURCE_EXHAUSTION"; default -> "UNKNOWN_OR_HEALTHY"; };
                    String cause=view.hypotheses().stream().map(ManagedOperationsService.HypothesisView::cause).distinct().reduce((a,b) -> a+","+b).orElse(null);
                    boolean passed=view.origin().equals("MODEL") && view.failureType()==null && !"PROPOSE_ACTION".equals(view.decision())
                        && (scenario.equals("historical") || view.hypotheses().stream().anyMatch(h -> h.cause().equals(expected)))
                        && (!scenario.equals("historical") || oracle(base.resolve("/business")));
                    write(caseRoot.resolve("external-oracle.json"),Map.of("expectedCause",expected,"currentBusinessExact",oracle(base.resolve("/business")),
                        "statePermission",service.status("api").permission(),"scenarioKind","CONTROLLED_FAULT_SIMULATION_WITH_ACTUAL_CONTAINER_EVIDENCE"));
                    results.add(new Result(scenario,passed,view.origin(),view.failureType(),cause,view.decision(),requests.get()-beforeRequests,tokens.get()-beforeTokens,null));
                } catch (Exception e) {
                    results.add(new Result(scenario,false,"SYSTEM",e.getClass().getSimpleName(),null,null,requests.get()-beforeRequests,tokens.get()-beforeTokens,e.getClass().getSimpleName()));
                } finally { write(caseRoot.resolve("model-exchanges.json"),exchanges); write(output.resolve("results.json"),results); }
            }
        } finally {
            if (owned) {
                if (!project.matches("clawkit-autonomy-diagnostic-[a-f0-9]{8}") || !compose.equals(repo.resolve("ops-fixtures/diagnostic-autonomy/compose.yaml").toRealPath()))
                    throw new IllegalStateException("cleanup ownership mismatch");
                var cleanup=process.execute(command(context,compose,project,List.of("down","--remove-orphans")),environment,Duration.ofSeconds(30),8192);
                write(output.resolve("cleanup.json"),cleanup); assertThat(cleanup.success()).isTrue();
                String remaining=read(process,List.of("docker","--context",context,"ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}"));
                write(output.resolve("cleanup-audit.json"),Map.of("remainingContainerIds",remaining,"passed",remaining.isEmpty())); assertThat(remaining).isEmpty();
            }
        }
        assertThat(results).hasSize(selected.size()).allMatch(Result::passed);
    }
    static final class RecordingProvider implements LLMProvider {
        final LLMProvider delegate; final List<OpsDecisionAgent.ProviderExchange> exchanges; final AtomicLong calls,tokens;
        final long maxRequests,maxTokens;
        RecordingProvider(LLMProvider delegate,List<OpsDecisionAgent.ProviderExchange> exchanges,AtomicLong calls,AtomicLong tokens,long maxRequests,long maxTokens) {
            this.delegate=delegate; this.exchanges=exchanges; this.calls=calls; this.tokens=tokens;
            this.maxRequests=maxRequests; this.maxTokens=maxTokens;
        }
        public ModelResponse generate(ModelRequest request) {
            if (calls.get()>=maxRequests || tokens.get()>=maxTokens) throw new LLMException("development budget exhausted");
            calls.incrementAndGet(); Instant start=Instant.now(); var recorded=new OpsDecisionAgent.RecordedRequest(request.messages(),request.tools(),request.parameters());
            try { var result=delegate.generate(request); tokens.addAndGet(result.usage().totalTokens());
                exchanges.add(new OpsDecisionAgent.ProviderExchange(start,Instant.now(),recorded,result,null)); return result;
            } catch (RuntimeException e) { var rejected=e instanceof LLMException failure ? failure.rejectedResponse() : null;
                if(rejected!=null) tokens.addAndGet(rejected.usage().totalTokens());
                exchanges.add(new OpsDecisionAgent.ProviderExchange(start,Instant.now(),recorded,null,e.getClass().getSimpleName(),rejected)); throw e;
            }
        }
        public Message generate(List<Message> messages,List<ToolDefinition> tools) { throw new AssertionError("typed provider required"); }
        public int getContextWindow() { return delegate.getContextWindow(); } public String getEncoding() { return delegate.getEncoding(); }
        public ProviderDescriptor descriptor() { return delegate.descriptor(); }
    }
    private static void inject(URI base,String mode) throws Exception {
        try (var http=HttpClient.newHttpClient()) {
            var result=http.send(HttpRequest.newBuilder(base.resolve("/__fixture/fault")).timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\""+mode+"\"}")).header("Content-Type","application/json").build(),HttpResponse.BodyHandlers.discarding());
            if (result.statusCode()!=200) throw new IllegalStateException("fixture injection failed");
        }
    }
    private static boolean oracle(URI uri) {
        try (var http=HttpClient.newHttpClient()) {
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString());
            return response.statusCode()==200 && JSON.readTree(response.body()).path("status").asText().equals("accepted");
        } catch (Exception e) { return false; }
    }
    private static List<String> command(String context,Path compose,String project,List<String> args) {
        var command=new ArrayList<>(List.of("docker","--context",context,"compose","-f",compose.toString(),"-p",project)); command.addAll(args); return command;
    }
    private static String read(CommandExecutor process,List<String> args) {
        var result=process.execute(args,Map.of(),Duration.ofSeconds(5),16384); if (!result.success() || result.truncated()) throw new IllegalStateException("bounded development read failed");
        return result.stdout().strip();
    }
    private static void write(Path path,Object value) throws Exception { Files.writeString(path,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private static int port() throws Exception { try (var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static String required(String name) { String value=System.getenv(name); if(value==null || value.isBlank()) throw new IllegalStateException(name+" required"); return value; }
}
