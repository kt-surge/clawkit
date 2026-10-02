package com.clawkit.ops.mcp;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.clawkit.ops.mcp.PinnedRestartContract.*;
import static org.assertj.core.api.Assertions.*;

class PinnedRestartServiceTest {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-10-02T01:00:00Z");
    final Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
    final AtomicInteger writes=new AtomicInteger();
    Configuration config() {
        return new Configuration(VERSION,new Target("default","daemon-1","unix:///var/run/docker.sock",root.resolve("compose.yaml").toString(),
            "a".repeat(64),"reviewed-orders","order-api","b".repeat(64),Map.of(),Map.of()),1,NOW.minusSeconds(10),NOW.plusSeconds(60),true,true,root.toString());
    }
    Request request(String incident,Configuration configuration) { return Request.of("att-"+UUID.randomUUID(),incident,configuration); }
    PinnedRestartService.Backend backend() {
        return new PinnedRestartService.Backend() {
            public void precheck(Target target) {}
            public CommandResult restart(Target target) { writes.incrementAndGet(); return new CommandResult(0,"","",false,false,0); }
        };
    }
    PinnedRestartService service(PinnedRestartService.ConfigurationSource source,PinnedRestartService.Backend backend) throws Exception {
        return new PinnedRestartService(root,source,backend,clock);
    }
    @Test void exactReplayReturnsDurableReceiptWhileConflictsAndGrantBudgetNeverDispatch() throws Exception {
        var config=config(); var service=service(() -> config,backend()); var request=request("inc-1",config);
        var first=service.restart(request);
        assertThat(first.status()).isEqualTo(Status.DISPATCH_REPORTED);
        assertThat(service.restart(request)).isEqualTo(first);
        assertThat(service.receipt(request.requestId())).isEqualTo(first);
        var conflicting=new Request(VERSION,request.requestId(),"other",request.targetHash(),request.configurationHash(),request.grantVersion(),request.grantExpiresAt());
        assertThat(service.restart(conflicting).code()).isEqualTo("REQUEST_ID_CONFLICT");
        assertThat(service.restart(request("inc-1",config)).code()).isEqualTo("INCIDENT_ALREADY_DISPATCHED");
        assertThat(service.restart(request("inc-2",config)).code()).isEqualTo("GRANT_BUDGET_EXHAUSTED");
        assertThat(writes).hasValue(1);
    }
    @Test void disabledExpiredAndChangedGrantsAreRefusedBeforeAnyBackendAction() throws Exception {
        var original=config();
        for(var live:List.of(new Configuration(VERSION,original.target(),1,NOW.minusSeconds(10),NOW.plusSeconds(60),false,true,root.toString()),
                new Configuration(VERSION,original.target(),1,NOW.minusSeconds(100),NOW.minusSeconds(1),true,true,root.toString()),
                new Configuration(VERSION,original.target(),2,original.issuedAt(),original.expiresAt(),true,true,root.toString()))) {
            var request=request("inc-"+UUID.randomUUID(),live);
            if(live.grantVersion()==2) request=request("inc-changed",original);
            assertThat(service(() -> live,backend()).restart(request).status()).isEqualTo(Status.REJECTED);
        }
        assertThat(writes).hasValue(0);
    }
    @Test void revocationDuringEachPrecheckPreventsMutationAndIntentRemainsQueryable() throws Exception {
        for(int revokeAt:List.of(1,2)) {
            Path directory=Files.createDirectory(root.resolve("check-"+revokeAt));
            var base=config(); var config=new Configuration(VERSION,base.target(),1,base.issuedAt(),base.expiresAt(),true,true,directory.toString());
            var live=new AtomicReference<>(config); var reads=new AtomicInteger();
            var backend=new PinnedRestartService.Backend() {
                public void precheck(Target target) { if(reads.incrementAndGet()==revokeAt) live.set(new Configuration(VERSION,config.target(),1,
                    config.issuedAt(),config.expiresAt(),false,true,directory.toString())); }
                public CommandResult restart(Target target) { writes.incrementAndGet(); return new CommandResult(0,"","",false,false,0); }
            };
            var service=new PinnedRestartService(directory,live::get,backend,clock); var request=request("inc-revoked",config);
            assertThat(service.restart(request).status()).isEqualTo(Status.REJECTED);
            if(revokeAt==2) assertThat(service.receipt(request.requestId()).status()).isEqualTo(Status.REJECTED);
        }
        assertThat(writes).hasValue(0);
    }
    @Test void lostOutcomeIsStickyAcrossRestartAndEvenANewGrantCannotEscapeUnknown() throws Exception {
        var config=config(); var request=request("inc-lost",config);
        var backend=new PinnedRestartService.Backend() {
            public void precheck(Target target) {}
            public CommandResult restart(Target target) throws Exception { writes.incrementAndGet(); throw new java.io.IOException("reply lost"); }
        };
        assertThat(service(() -> config,backend).restart(request).status()).isEqualTo(Status.UNKNOWN);
        var newGrant=new Configuration(VERSION,config.target(),2,config.issuedAt(),config.expiresAt(),true,true,root.toString());
        var reopened=service(() -> newGrant,backend());
        assertThat(reopened.restart(request).status()).isEqualTo(Status.UNKNOWN);
        assertThat(reopened.restart(request("inc-new",newGrant)).code()).isEqualTo("UNRESOLVED_DISPATCH");
        assertThat(writes).hasValue(1);
    }
    @Test void intentWithoutReceiptMeansUnknownAndCorruptHistoryBlocksAllWrites() throws Exception {
        var config=config(); var request=request("inc-crash",config);
        Files.write(root.resolve(request.requestId()+".intent.json"),JSON.writeValueAsBytes(new Intent(VERSION,request,request.fingerprint(),NOW)));
        var service=service(() -> config,backend());
        assertThat(service.restart(request).status()).isEqualTo(Status.UNKNOWN);
        assertThat(service.restart(request("inc-next",config)).status()).isEqualTo(Status.REJECTED);
        Files.writeString(root.resolve(request.requestId()+".intent.json"),"{partial");
        assertThatThrownBy(() -> service.restart(request("inc-corrupt",config))).isInstanceOf(Exception.class);
        assertThat(writes).hasValue(0);
    }
    @Test void twoServerInstancesCannotDispatchConcurrently() throws Exception {
        var config=config(); var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        var blocking=new PinnedRestartService.Backend() {
            public void precheck(Target target) {}
            public CommandResult restart(Target target) throws Exception {
                writes.incrementAndGet(); entered.countDown();
                if(!release.await(5,TimeUnit.SECONDS)) throw new java.io.IOException("bounded wait");
                return new CommandResult(0,"","",false,false,0);
            }
        };
        try(var executor=Executors.newSingleThreadExecutor()) {
            var request=request("inc-running",config);
            var first=executor.submit(() -> service(() -> config,blocking).restart(request));
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(service(() -> config,backend()).restart(request("inc-parallel",config)).code()).isEqualTo("TARGET_BUSY");
            } finally { release.countDown(); }
            assertThat(first.get(3,TimeUnit.SECONDS).status()).isEqualTo(Status.DISPATCH_REPORTED);
        }
        assertThat(writes).hasValue(1);
    }
    @Test void receiptWithoutItsIntentIsHistoryLossAndCannotBeRedispatched() throws Exception {
        var config=config(); var request=request("inc-history-loss",config);
        Files.write(root.resolve(request.requestId()+".receipt.json"),JSON.writeValueAsBytes(PinnedRestartContract.receipt(request,Status.DISPATCH_REPORTED,"COMMAND_REPORTED_SUCCESS",NOW)));
        assertThatThrownBy(() -> service(() -> config,backend()).restart(request)).hasMessageContaining("orphan receipt");
        assertThat(writes).hasValue(0);
    }
    @Test void malformedArgumentsAndLegacyProfileCannotReachNewBackend() throws Exception {
        var config=config(); var server=new OpsMcpServer(null,OpsCapabilityProfile.PINNED_RESTART_V2,null,service(() -> config,backend()));
        var args=JSON.valueToTree(request("inc-schema",config)); ((com.fasterxml.jackson.databind.node.ObjectNode)args).put("command","arbitrary");
        var params=JSON.createObjectNode().put("name","restart_pinned"); params.set("arguments",args);
        assertThat(server.handle(JSON.getNodeFactory().numberNode(1),"tools/call",params).has("error")).isTrue();
        assertThat(new OpsMcpServer(null,OpsCapabilityProfile.FIX_ORDER_API_V1).handle(JSON.getNodeFactory().numberNode(2),"tools/call",params).has("error")).isTrue();
        assertThat(server.handle(JSON.getNodeFactory().numberNode(3),"initialize",JSON.createObjectNode()).path("result").path("serverInfo").path("probeVersion").asText()).isEqualTo("2");
        assertThat(writes).hasValue(0);
    }
}
