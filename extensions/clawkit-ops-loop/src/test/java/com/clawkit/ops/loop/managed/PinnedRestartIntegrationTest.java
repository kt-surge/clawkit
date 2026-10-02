package com.clawkit.ops.loop.managed;

import com.clawkit.observability.CompositeRunRecorder;
import com.clawkit.ops.mcp.*;
import com.clawkit.reliability.attempt.AttemptState;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.mcp.*;
import com.clawkit.tools.remote.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static com.clawkit.ops.mcp.PinnedRestartContract.*;
import static org.assertj.core.api.Assertions.*;

/** Real MCP parsing/attestation + original execution gate/journal/verifier. Remote facts are controlled, not a production trial. */
class PinnedRestartIntegrationTest {
    @TempDir Path root;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-02T01:00:00Z"),ZoneOffset.UTC);
    final ManagedApplication app=new ManagedApplication("orders","remote-fixture","reviewed-orders","order-api",1,true,
        ManagedApplication.DesiredState.RUNNING,null,URI.create("http://127.0.0.1:18080/health"),URI.create("http://127.0.0.1:18080/orders"),
        "accepted",Duration.ofSeconds(5),Duration.ofSeconds(90));
    final AtomicInteger writes=new AtomicInteger(),observations=new AtomicInteger(),sessions=new AtomicInteger(),closed=new AtomicInteger();
    boolean healthy,dropReply,recoverOnRestart=true,revokeOnPrecheck;
    Configuration configuration;
    final AtomicReference<Configuration> live=new AtomicReference<>();
    OpsMcpServer server;
    PinnedRestartService service;
    void initialize(String suffix) throws Exception {
        Path directory=Files.createDirectory(root.resolve(suffix));
        configuration=new Configuration(VERSION,new Target("default","daemon-1","unix:///var/run/docker.sock",directory.resolve("compose.yaml").toString(),
            "a".repeat(64),app.composeProject(),app.service(),"b".repeat(64),Map.of(),Map.of()),1,clock.instant().minusSeconds(10),clock.instant().plusSeconds(600),true,true,directory.resolve("server").toString());
        live.set(configuration);
        service=new PinnedRestartService(Path.of(configuration.stateDirectory()),live::get,new PinnedRestartService.Backend() {
            public void precheck(Target target) {
                if(healthy) throw new IllegalStateException("self recovered");
                if(revokeOnPrecheck) live.set(new Configuration(VERSION,target,1,configuration.issuedAt(),configuration.expiresAt(),false,true,configuration.stateDirectory()));
            }
            public CommandResult restart(Target target) { writes.incrementAndGet(); healthy=recoverOnRestart; return new CommandResult(0,"","",false,false,0); }
        },clock);
        server=new OpsMcpServer(null,OpsCapabilityProfile.PINNED_RESTART_V2,null,service);
    }
    @Test void humanAndPolicyUseSameClientAttemptServerReceiptAndThreeIndependentSamples() throws Exception {
        for(boolean human:List.of(false,true)) {
            healthy=false; initialize(human ? "human" : "policy"); var proposal=proposal(); String incident=human ? "inc-human" : "inc-policy";
            var request=human ? RepairAuthorization.approved(incident,app,proposal.decision(),proposal.evidence(),"fixture-review",clock) : RepairAuthorization.Automatic.INSTANCE;
            var fix=new PinnedRestartAdapter(app,configuration,this::session,clock);
            try(var executor=executor(root.resolve(human ? "human-client" : "policy-client"))) {
                var outcome=execute(executor,incident,proposal,request,fix);
                assertThat(outcome.status()).isEqualTo(ManagedRepairExecutor.Status.RECOVERED);
                assertThat(outcome.attemptState()).isEqualTo(AttemptState.VERIFIED_SUCCESS);
                assertThat(outcome.verification().samples()).hasSize(3).allMatch(IndependentManagedVerifier.Sample::healthy);
                var receipt=service.receipt(outcome.attemptId());
                assertThat(receipt.request().requestId()).isEqualTo(outcome.attemptId());
                assertThat(receipt.request().incidentId()).isEqualTo(incident);
                assertThat(receipt.status()).isEqualTo(PinnedRestartContract.Status.DISPATCH_REPORTED);
                assertThat(execute(executor,incident,proposal,request,fix).status()).isEqualTo(ManagedRepairExecutor.Status.BLOCKED);
            }
        }
        assertThat(writes).hasValue(2); assertThat(observations).hasValue(8);
        assertThat(sessions).hasValue(2); assertThat(closed).hasValue(2);
    }
    @Test void lostClientReplyNeverRedispatchesAfterRestartEvenWhenServerReportedSuccess() throws Exception {
        initialize("lost"); var proposal=proposal(); dropReply=true;
        var fix=new PinnedRestartAdapter(app,configuration,this::session,clock); String attempt;
        try(var executor=executor(root.resolve("lost-client"))) {
            var outcome=execute(executor,"inc-lost",proposal,RepairAuthorization.Automatic.INSTANCE,fix); attempt=outcome.attemptId();
            assertThat(outcome.status()).isEqualTo(ManagedRepairExecutor.Status.OUTCOME_UNKNOWN);
            assertThat(outcome.attemptState()).isEqualTo(AttemptState.OUTCOME_UNKNOWN); assertThat(outcome.verification()).isNull();
        }
        dropReply=false;
        try(var executor=executor(root.resolve("lost-client"))) {
            executor.recover(app);
            assertThat(execute(executor,"inc-next",proposal,RepairAuthorization.Automatic.INSTANCE,fix).status()).isEqualTo(ManagedRepairExecutor.Status.REQUIRES_APPROVAL);
            try(var read=session()) { assertThat(read.receipt(attempt).status()).isEqualTo(PinnedRestartContract.Status.DISPATCH_REPORTED); }
            assertThat(executor.attempts().byId(attempt).orElseThrow().state()).isEqualTo(AttemptState.OUTCOME_UNKNOWN);
        }
        assertThat(writes).hasValue(1); assertThat(observations).hasValue(1); assertThat(closed).hasValue(sessions.get());
    }
    @Test void serverRefusalIsNoEffectWhileCommandSuccessWithoutBusinessRecoveryFailsVerification() throws Exception {
        initialize("revoked"); revokeOnPrecheck=true; var proposal=proposal();
        try(var executor=executor(root.resolve("revoked-client"))) {
            var result=execute(executor,"inc-revoked",proposal,RepairAuthorization.Automatic.INSTANCE,new PinnedRestartAdapter(app,configuration,this::session,clock));
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.NO_EFFECT); assertThat(result.verification()).isNull();
        }
        assertThat(writes).hasValue(0);
        revokeOnPrecheck=false; recoverOnRestart=false; initialize("persistent"); proposal=proposal();
        try(var executor=executor(root.resolve("persistent-client"))) {
            var result=execute(executor,"inc-persistent",proposal,RepairAuthorization.Automatic.INSTANCE,new PinnedRestartAdapter(app,configuration,this::session,clock));
            assertThat(result.status()).isEqualTo(ManagedRepairExecutor.Status.VERIFICATION_FAILED);
            assertThat(result.attemptState()).isEqualTo(AttemptState.ESCALATED); assertThat(result.verification().recovered()).isFalse();
        }
        assertThat(writes).hasValue(1);
    }
    @Test void ordinaryReadonlySessionCannotAttestPrivateWriteProfile() throws Exception {
        initialize("read-only");
        var transport=new Wire(); var peer=peer(transport,Set.of());
        try(peer) { assertThatThrownBy(peer::doInitializeAndAttestForAdapter).isInstanceOf(IOException.class); assertThat(peer.isReady()).isFalse(); }
        assertThat(writes).hasValue(0);
    }
    @Test void explicitWriteToolAbsentFromReadonlyProfileLeavesSessionFailed() throws Exception {
        initialize("absent"); server=new OpsMcpServer(null,OpsCapabilityProfile.APP_DOWN_V1);
        var descriptor=new RemoteTargetDescriptor("remote-fixture","clawkit-ops-mcp",OpsMcpServer.PROTOCOL_VERSION,OpsMcpServer.PROBE_VERSION,
            OpsCapabilityProfile.APP_DOWN_V1.name(),OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.APP_DOWN_V1),
            OpsMcpServer.computeExpectedToolContractHash(OpsCapabilityProfile.APP_DOWN_V1));
        var wire=new Wire();
        try(var peer=peer(wire,Set.of("absent-write"),descriptor)) {
            assertThatThrownBy(peer::doInitializeAndAttestForAdapter).isInstanceOf(IOException.class);
            assertThat(peer.internalState()).isEqualTo(RemoteMcpSession.InternalState.FAILED);
        }
        assertThat(wire.isAlive()).isFalse(); assertThat(writes).hasValue(0);
    }
    private record Proposal(OpsDecision decision,List<DecisionEvidence> evidence) {}
    private Proposal proposal() throws Exception {
        var ledger=new DecisionEvidenceLedger(app,clock);
        for(var probe:List.of(Probe.SERVICE,Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES)) ledger.collect(this::read,probe);
        var decision=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"controlled unhealthy service",ledger.snapshot().stream().map(DecisionEvidence::id).toList(),
            OpsDecision.Playbook.RESTART_UNHEALTHY_V1,List.of(),null);
        ledger.submit(decision); return new Proposal(decision,ledger.snapshot());
    }
    private Observation read(ManagedApplication app,Probe probe) {
        return new Observation(app.targetId(),app.composeProject(),app.service(),probe,clock.instant(),probe==Probe.SERVICE ? ManagedObserver.Status.RUNNING
            : probe==Probe.DEPENDENCIES || healthy ? ManagedObserver.Status.HEALTHY : ManagedObserver.Status.UNHEALTHY,"controlled independent read");
    }
    private ManagedRepairExecutor executor(Path path) throws Exception {
        return new ManagedRepairExecutor(path,clock,new CompositeRunRecorder(),new IndependentManagedVerifier(clock,
            new IndependentManagedVerifier.Settings(3,3,Duration.ofMillis(100)),interval -> {}));
    }
    private ManagedRepairExecutor.Outcome execute(ManagedRepairExecutor executor,String incident,Proposal proposal,RepairAuthorization.Request request,ManagedFixAdapter fix) {
        var policy=new ActionPolicy(app.id(),1,1,ActionPolicy.Mode.LIMITED_AUTO,ActionPolicy.Qualification.QUALIFIED,
            Set.of(OpsDecision.Playbook.RESTART_UNHEALTHY_V1),clock.instant().plusSeconds(600),1);
        return executor.execute(incident,app,proposal.decision(),proposal.evidence(),request,() -> app,() -> policy,
            () -> { observations.incrementAndGet(); return this::read; },fix,ExecutionControl.none());
    }
    private PinnedRestartAdapter.Session session() throws Exception {
        sessions.incrementAndGet(); var peer=peer(new Wire(),Set.of("restart_pinned"));
        try { peer.doInitializeAndAttestForAdapter(); return PinnedRestartMcpSession.fromReady(peer); }
        catch(Exception e) { peer.close(); throw e; }
    }
    private RemoteMcpSession peer(Wire transport,Set<String> writes) {
        return peer(transport,writes,PinnedRestartMcpSession.descriptor("remote-fixture"));
    }
    private RemoteMcpSession peer(Wire transport,Set<String> writes,RemoteTargetDescriptor descriptor) {
        var connection=new RemoteSshConnectionSpec() {
            public List<String> sshArgs() { throw new AssertionError("controlled test must never start SSH"); }
            public String safeRef() { return "controlled"; }
            public Duration connectTimeout() { return Duration.ofSeconds(5); }
            public Duration requestTimeout() { return Duration.ofSeconds(5); }
            public int maxOutputBytes() { return 32768; }
        };
        return new RemoteMcpSession(descriptor,connection,clock,transport,new McpClient(transport,"remote-fixture"),writes);
    }
    private final class Wire implements McpTransport {
        boolean alive=true;
        public void start() {}
        public String send(String input) throws IOException {
            var response=new ByteArrayOutputStream(); server.serve(new ByteArrayInputStream((input+"\n").getBytes(StandardCharsets.UTF_8)),response);
            if(dropReply && input.contains("restart_pinned")) throw new IOException("lost after server execution");
            return response.toString(StandardCharsets.UTF_8).strip();
        }
        public boolean isAlive() { return alive; }
        public void stop() { if(alive) { alive=false; closed.incrementAndGet(); } }
    }
}
