package com.clawkit.ops.delivery.managed;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.*;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.*;
import com.clawkit.tools.remote.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Explicit disposable local fixture. No SSH, model or registered remote target can enter this harness. */
public final class PinnedRestartFixtureEvidenceMain {
    private static final Clock CLOCK=Clock.systemUTC();
    private static final CommandExecutor COMMANDS=new ProcessCommandExecutor();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private PinnedRestartFixtureEvidenceMain() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("one fresh output directory under workspace tmp required");
        Path workspace=Path.of(".").toRealPath(); Path output=workspace.resolve(args[0]).toAbsolutePath().normalize();
        Path temporary=workspace.resolve("tmp").toRealPath();
        if(!output.startsWith(temporary) || output.equals(temporary) || Files.exists(output)) throw new IllegalArgumentException("fresh workspace tmp directory required");
        Files.createDirectories(output); output=output.toRealPath();
        if(!output.startsWith(temporary)) throw new IllegalArgumentException("output directory escaped workspace tmp");
        String context=read(List.of("docker","context","show"));
        String endpoint=read(List.of("docker","context","inspect",context,"--format","{{.Endpoints.docker.Host}}"));
        if(!(endpoint.startsWith("npipe:////./pipe/") || endpoint.startsWith("unix:///"))) throw new IllegalArgumentException("local Docker only");
        var docker=List.of("docker","--host",endpoint);
        if(!"linux".equals(read(join(docker,List.of("info","--format","{{.OSType}}"))))) throw new IllegalArgumentException("Linux daemon required");
        String daemon=read(join(docker,List.of("info","--format","{{.ID}}")));
        String project="clawkit-autonomy-r2-"+UUID.randomUUID().toString().substring(0,8);
        if(!read(join(docker,List.of("ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}"))).isEmpty())
            throw new IllegalStateException("owned fixture project collision");
        int ordersPort=port(),catalogPort=port(); while(catalogPort==ordersPort) catalogPort=port();
        String compose=Files.readString(workspace.resolve("ops-fixtures/layered-autonomy/compose.yaml"))
            .replaceFirst("(?m)^  orders:\\r?$","  order-api:")
            .replace("${AUTONOMY_ORDERS_PORT:-18180}",Integer.toString(ordersPort))
            .replace("${AUTONOMY_CATALOG_PORT:-18181}",Integer.toString(catalogPort));
        Path composePath=output.resolve("compose.yaml"); Files.writeString(composePath,compose);
        Files.copy(workspace.resolve("ops-fixtures/layered-autonomy/server.py"),output.resolve("server.py"));
        var composeCommand=join(docker,List.of("compose","--ansi","never","-f",composePath.toString(),"-p",project));
        boolean created=false; var results=new ArrayList<Map<String,Object>>();
        try {
            created=true;
            require(COMMANDS.execute(join(composeCommand,List.of("up","-d","--wait","--wait-timeout","60")),Map.of(),Duration.ofSeconds(90),8192).success(),"fixture up failed");
            var target=IsolatedComposeClient.register(COMMANDS,composePath,context,project,"order-api",List.of("catalog"));
            var fixed=new PinnedRestartContract.Target(target.context(),target.daemonId(),target.endpoint(),target.composeFile(),target.composeHash(),
                target.project(),target.service(),target.containerId(),target.dependencies(),target.mounts());
            URI health=URI.create("http://127.0.0.1:"+ordersPort+"/health"),business=URI.create("http://127.0.0.1:"+ordersPort+"/orders");
            var app=new ManagedApplication("orders-fixture","local-isolated",project,"order-api",1,true,ManagedApplication.DesiredState.RUNNING,null,
                health,business,"accepted",Duration.ofSeconds(5),Duration.ofSeconds(90));
            var live=new AtomicReference<>(scope(fixed,output.resolve("server"),1));
            var service=new PinnedRestartService(output.resolve("server"),live::get,new PinnedDockerRestart(COMMANDS),CLOCK);
            var server=new OpsMcpServer(null,OpsCapabilityProfile.PINNED_RESTART_V2,null,service);
            var verifier=new IndependentManagedVerifier(CLOCK,IndependentManagedVerifier.Settings.defaults(),duration -> Thread.sleep(duration.toMillis()));
            var reads=(IndependentManagedVerifier.ObserverFactory) () -> new ComposeManagedAdapter(new IsolatedComposeClient(target,COMMANDS),CLOCK);
            var policy=new ActionPolicy(app.id(),1,1,ActionPolicy.Mode.LIMITED_AUTO,ActionPolicy.Qualification.QUALIFIED,
                Set.of(OpsDecision.Playbook.RESTART_UNHEALTHY_V1),CLOCK.instant().plusSeconds(900),1);
            Path client=output.resolve("client"); String unresolvedAttempt;
            try(var executor=new ManagedRepairExecutor(client,CLOCK,new CompositeRunRecorder(),verifier)) {
                inject(health); waitUnhealthy(target);
                var proposal=proposal(app,reads);
                var fix=new PinnedRestartAdapter(app,live.get(),() -> session(server,false),CLOCK);
                var result=executor.execute("fixture-success",app,proposal.decision(),proposal.evidence(),RepairAuthorization.Automatic.INSTANCE,
                    () -> app,() -> policy,reads,fix,ExecutionControl.none());
                require(result.status()==ManagedRepairExecutor.Status.RECOVERED,"independent recovery failed");
                var receipt=service.receipt(result.attemptId()); require(receipt.request().requestId().equals(result.attemptId()),"Attempt ids differ");
                require(result.verification().samples().size()>=3 && result.verification().samples().subList(result.verification().samples().size()-3,result.verification().samples().size())
                    .stream().allMatch(IndependentManagedVerifier.Sample::healthy),"three independent samples required");
                var oracleReads=oracle(business); String started=started(docker,target.containerId());
                require(executor.execute("fixture-success",app,proposal.decision(),proposal.evidence(),RepairAuthorization.Automatic.INSTANCE,
                    () -> app,() -> policy,reads,fix,ExecutionControl.none()).status()==ManagedRepairExecutor.Status.BLOCKED,"duplicate client action was not blocked");
                require(service.restart(receipt.request()).equals(receipt),"server replay differs");
                require(started.equals(started(docker,target.containerId())),"duplicate request changed actual container start time");
                results.add(Map.of("case","policy-recovery","status",result.status().name(),"attemptId",result.attemptId(),
                    "serverStatus",receipt.status().name(),"independentSamples",result.verification().samples().size(),"externalBusinessOracle",true,
                    "externalOracleObservations",oracleReads,"duplicateRestartObserved",false));
                live.set(scope(fixed,output.resolve("server"),2)); inject(health); waitUnhealthy(target);
                proposal=proposal(app,reads); fix=new PinnedRestartAdapter(app,live.get(),() -> session(server,true),CLOCK);
                var unknown=executor.execute("fixture-lost-reply",app,proposal.decision(),proposal.evidence(),RepairAuthorization.Automatic.INSTANCE,
                    () -> app,() -> policy,reads,fix,ExecutionControl.none());
                require(unknown.status()==ManagedRepairExecutor.Status.OUTCOME_UNKNOWN,"lost reply was not unknown");
                unresolvedAttempt=unknown.attemptId();
                require(unknown.verification()==null,"unknown must not be promoted by automatic verification"); oracleReads=oracle(business);
                var recorded=service.receipt(unknown.attemptId()); require(recorded.status()==PinnedRestartContract.Status.DISPATCH_REPORTED,"server dispatch receipt absent");
                results.add(Map.of("case","lost-client-reply","status",unknown.status().name(),"attemptId",unknown.attemptId(),"serverStatus",recorded.status().name(),
                    "independentSamples",0,"externalBusinessOracle",true,"externalOracleObservations",oracleReads));
            }
            try(var restarted=new ManagedRepairExecutor(client,CLOCK,new CompositeRunRecorder(),verifier)) {
                restarted.recover(app); var proposal=proposalFromFailure(app,reads);
                String started=started(docker,target.containerId());
                var result=restarted.execute("fixture-after-restart",app,proposal.decision(),proposal.evidence(),RepairAuthorization.Automatic.INSTANCE,
                    () -> app,() -> policy,reads,new PinnedRestartAdapter(app,live.get(),() -> session(server,false),CLOCK),ExecutionControl.none());
                require(result.status()==ManagedRepairExecutor.Status.BLOCKED,"healthy current evidence must not justify another restart");
                require(restarted.controlStore().degradation(app.id()).isPresent(),"unknown downgrade did not survive restart");
                require(restarted.attempts().byId(unresolvedAttempt).orElseThrow().state()
                    ==com.clawkit.reliability.attempt.AttemptState.OUTCOME_UNKNOWN,"unknown journal state was lost");
                require(started.equals(started(docker,target.containerId())),"controller restart redispatched");
                results.add(Map.of("case","client-restart-after-unknown","status",result.status().name(),"unknownAndDowngradePersisted",true,"duplicateRestartObserved",false));
            }
        } finally {
            if(created) {
                require(daemon.equals(read(join(docker,List.of("info","--format","{{.ID}}")))),"daemon changed; do not clean a different daemon");
                require(COMMANDS.execute(join(composeCommand,List.of("down","--remove-orphans")),Map.of(),Duration.ofSeconds(45),8192).success(),"owned fixture cleanup failed");
            }
            require(read(join(docker,List.of("ps","-a","--filter","label=com.docker.compose.project="+project,"--format","{{.ID}}"))).isEmpty(),"fixture residue remains");
        }
        String classpath=System.getProperty("java.class.path");
        String jarHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(classpath))));
        var summary=Map.of("kind","LOCAL_DOCKER_PINNED_RESTART_INTEGRATION","at",CLOCK.instant(),"candidateJarSha256",jarHash,
            "cases",results,"modelRequests",0,"sshSessions",0,"serverRestartReceipts",2,"cleanupContainerCount",0,
            "limits",List.of("controlled disposable fixture, not real remote repair or a model benchmark","MCP uses in-process transport; real SSH deployment remains untested",
                "Windows directory fsync is unavailable; this is not power-loss certification"));
        Files.write(output.resolve("summary.json"),PinnedRestartContract.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(summary));
        System.out.println("Pinned restart fixture accepted: "+output.resolve("summary.json"));
    }
    private record Proposal(OpsDecision decision,List<DecisionEvidence> evidence) {}
    private static Proposal proposal(ManagedApplication app,IndependentManagedVerifier.ObserverFactory reads) throws Exception {
        var facts=collect(app,reads);
        var decision=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"isolated fixture unhealthy",facts.stream().map(DecisionEvidence::id).toList(),
            OpsDecision.Playbook.RESTART_UNHEALTHY_V1,List.of(),null); return new Proposal(decision,facts);
    }
    private static Proposal proposalFromFailure(ManagedApplication app,IndependentManagedVerifier.ObserverFactory reads) throws Exception {
        // Actual healthy reads must refuse another proposal; persisted unknown state is checked separately.
        var facts=collect(app,reads);
        return new Proposal(new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"attempt remains unresolved",facts.stream().map(DecisionEvidence::id).toList(),
            OpsDecision.Playbook.RESTART_UNHEALTHY_V1,List.of(),null),facts);
    }
    private static List<DecisionEvidence> collect(ManagedApplication app,IndependentManagedVerifier.ObserverFactory reads) throws Exception {
        var facts=new ArrayList<DecisionEvidence>();
        try(var observer=reads.open()) {
            for(var probe:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) {
                var observation=observer.observe(app,probe); String id="ev-"+UUID.randomUUID();
                facts.add(new DecisionEvidence(id,app.id(),app.version(),observation,observation.observedAt().plus(app.evidenceTtl())));
            }
        }
        return List.copyOf(facts);
    }
    private static PinnedRestartContract.Configuration scope(PinnedRestartContract.Target target,Path directory,long version) {
        return new PinnedRestartContract.Configuration(2,target,version,CLOCK.instant(),CLOCK.instant().plusSeconds(900),true,true,directory.toString());
    }
    private static PinnedRestartAdapter.Session session(OpsMcpServer server,boolean drop) throws Exception {
        var transport=new McpTransport() {
            boolean alive=true;
            public void start() {}
            public String send(String input) throws IOException {
                var output=new ByteArrayOutputStream(); server.serve(new ByteArrayInputStream((input+"\n").getBytes(StandardCharsets.UTF_8)),output);
                if(drop && input.contains("restart_pinned")) throw new IOException("fixture reply lost after server action");
                return output.toString(StandardCharsets.UTF_8).strip();
            }
            public boolean isAlive() { return alive; }
            public void stop() { alive=false; }
        };
        var connection=new RemoteSshConnectionSpec() {
            public List<String> sshArgs() { throw new AssertionError("SSH prohibited in local fixture"); }
            public String safeRef() { return "local-fixture"; }
            public Duration connectTimeout() { return Duration.ofSeconds(2); }
            public Duration requestTimeout() { return Duration.ofSeconds(90); }
            public int maxOutputBytes() { return 32768; }
        };
        var peer=new RemoteMcpSession(PinnedRestartMcpSession.descriptor("local-fixture"),connection,CLOCK,transport,new McpClient(transport,"local-fixture"),Set.of("restart_pinned"));
        try { peer.doInitializeAndAttestForAdapter(); return PinnedRestartMcpSession.fromReady(peer); }
        catch(Exception e) { peer.close(); throw e; }
    }
    private static void inject(URI health) throws Exception {
        var uri=URI.create(health.toString().replace("/health","/__fixture/fault"));
        var reply=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString("{\"seconds\":3600}")).build(),HttpResponse.BodyHandlers.discarding());
        require(reply.statusCode()==200,"isolated fault injection failed");
    }
    private static void waitUnhealthy(IsolatedComposeClient.Target target) throws Exception {
        Instant until=CLOCK.instant().plusSeconds(30);
        while(CLOCK.instant().isBefore(until)) {
            if("unhealthy".equals(new IsolatedComposeClient(target,COMMANDS).service().health())) return;
            Thread.sleep(500);
        }
        throw new IllegalStateException("fixture did not become unhealthy");
    }
    private static List<Map<String,Object>> oracle(URI uri) throws Exception {
        var observations=new ArrayList<Map<String,Object>>(); Instant until=CLOCK.instant().plusSeconds(20);
        while(CLOCK.instant().isBefore(until)) {
            Instant at=CLOCK.instant();
            try {
                var reply=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
                var body=PinnedRestartContract.JSON.readTree(reply.body());
                boolean matched=reply.statusCode()==200 && "accepted".equals(body.path("status").asText()) && "sample-001".equals(body.path("orderId").asText());
                observations.add(Map.of("at",at,"httpStatus",reply.statusCode(),"exactBodyMatched",matched));
                if(matched) return List.copyOf(observations);
            } catch(IOException e) { observations.add(Map.of("at",at,"error",e.getClass().getSimpleName())); }
            Thread.sleep(500);
        }
        throw new IllegalStateException("external business oracle failed after "+observations.size()+" bounded reads");
    }
    private static String started(List<String> docker,String id) throws Exception { return read(join(docker,List.of("inspect","--format","{{.State.StartedAt}}",id))); }
    private static int port() throws IOException { try(var socket=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static String read(List<String> command) {
        var result=COMMANDS.execute(command,Map.of(),Duration.ofSeconds(10),16384); require(result.success() && !result.truncated(),"bounded fixture command failed"); return result.stdout().strip();
    }
    private static List<String> join(List<String> first,List<String> second) { var result=new ArrayList<>(first); result.addAll(second); return List.copyOf(result); }
    private static void require(boolean condition,String message) { if(!condition) throw new IllegalStateException(message); }
}
