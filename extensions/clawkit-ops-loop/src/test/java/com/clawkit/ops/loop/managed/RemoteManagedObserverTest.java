package com.clawkit.ops.loop.managed;

import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static com.clawkit.ops.loop.managed.EvidenceEnvelope.*;
import static org.assertj.core.api.Assertions.*;

class RemoteManagedObserverTest {
    static final Instant NOW=Instant.parse("2026-10-01T13:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final String CONTAINER="a".repeat(12);
    static final ObjectMapper JSON=new ObjectMapper();
    static RemoteObservationBinding binding(RemoteObservationBinding.Endpoint health,RemoteObservationBinding.Endpoint business) {
        return new RemoteObservationBinding("remote-test","remote-fixture","order-api",CONTAINER,health,business,null,List.of());
    }
    static RemoteObservationBinding.Endpoint endpoint(String name,String path,String marker) {
        return new RemoteObservationBinding.Endpoint(name,URI.create("http://127.0.0.1:18080"+path),marker);
    }
    @Test void actualRemoteTimeAndInspectFactsAreKeptWithoutInventingMemoryOrWriteQualification() throws Exception {
        var source=binding(null,null); var app=source.application("orders",1,Duration.ofSeconds(5)); var session=new Session();
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            var service=observer.observe(app,Probe.SERVICE);
            assertThat(service.status()).isEqualTo(Status.RUNNING);
            assertThat(service.observedAt()).isEqualTo(NOW.minusSeconds(1));
            assertThat(service.payload().collection().source()).isEqualTo(Source.SSH_MCP);
            var resources=observer.observe(app,Probe.RESOURCES);
            assertThat(resources.payload().resources().oomKilled()).isFalse();
            assertThat(resources.payload().resources().exitCode()).isZero();
            assertThat(resources.payload().resources().memoryUsageBytes()).isNull();
            assertThat(resources.payload().resources().memoryLimitBytes()).isNull();
            assertThat(resources.payload().collection().limitation()).contains("快照","未转换");
            assertThat(app.repairIntendedAt(NOW)).isFalse();
            assertThat(session.calls).containsExactly("container_status","container_resources","container_status");
        }
        assertThat(session.closed).isTrue();
    }
    @Test void missingEndpointsAndTopologyProduceExplicitGapsWithoutRemoteCalls() throws Exception {
        var source=binding(null,null); var session=new Session();
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            for(var probe:List.of(Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES,Probe.CHANGES,Probe.METRICS)) {
                var result=observer.observe(source.application("orders",1,Duration.ofSeconds(5)),probe);
                assertThat(result.status()).isEqualTo(Status.UNKNOWN);
                assertThat(result.payload().collection().quality()).isEqualTo(Quality.MISSING);
            }
        }
        assertThat(session.calls).isEmpty();
    }
    @Test void boundedServerClockLeadWaitsWithoutRestampingSourceTimeAndLargeLeadStillFails() throws Exception {
        var time=new java.util.concurrent.atomic.AtomicReference<>(NOW); var waits=new ArrayList<Duration>();
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
        var source=binding(null,null); var session=new Session();
        session.modify=(tool,body) -> body.put("observedAt",NOW.plusSeconds(1).toString()).put("collectedAt",NOW.plusSeconds(1).toString());
        try(var observer=new RemoteManagedObserver(source,session,clock,null,d -> { waits.add(d); time.set(time.get().plus(d)); })) {
            var fact=observer.observe(source.application("orders",1,Duration.ofSeconds(5)),Probe.SERVICE);
            assertThat(fact.observedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(fact.payload().collection().collectedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(fact.payload().collection().quality()).isEqualTo(Quality.COMPLETE); assertThat(waits).hasSize(1);
            var ref=new DecisionEvidence("remote-clock","orders",1,fact,fact.observedAt().plusSeconds(90));
            assertThat(ref.currentAt(clock.instant())).isTrue();
            session.modify=(tool,body) -> body.put("observedAt",clock.instant().plusSeconds(6).toString()).put("collectedAt",clock.instant().plusSeconds(6).toString());
            assertThat(observer.observe(source.application("orders",1,Duration.ofSeconds(5)),Probe.SERVICE).payload().collection().quality()).isEqualTo(Quality.ERROR);
            assertThat(waits).hasSize(1);
        }
    }
    @Test void missingFactsUseOneCollectionInstantEvenWhenClockAdvances() throws Exception {
        var time=new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.getAndUpdate(t -> t.plusMillis(1)); }
        };
        var source=binding(null,null); var session=new Session();
        try(var observer=new RemoteManagedObserver(source,session,clock,null)) {
            for(var probe:List.of(Probe.HEALTH,Probe.BUSINESS,Probe.DEPENDENCIES,Probe.CHANGES,Probe.METRICS)) {
                var fact=observer.observe(source.application("orders",1,Duration.ofSeconds(5)),probe);
                assertThat(fact.payload().collection().quality()).isEqualTo(Quality.MISSING);
                assertThat(fact.observedAt()).isEqualTo(fact.payload().collection().windowStart())
                        .isEqualTo(fact.payload().collection().windowEnd()).isEqualTo(fact.payload().collection().collectedAt());
            }
        }
        assertThat(session.calls).isEmpty();
    }
    @Test void metricsHealthAndArbitraryRemoteUrlsCannotBecomeBusinessPredicates() {
        assertThatThrownBy(() -> binding(null,endpoint("metrics","/metrics","accepted"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding(null,endpoint("health","/health","ok"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> binding(endpoint("health","/orders",""),endpoint("business","/orders","accepted")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RemoteObservationBinding.Endpoint("outside",URI.create("http://example.com/orders"),"ok"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ManagedApplication("orders","local-isolated","fixture","orders",1,true,
                ManagedApplication.DesiredState.RUNNING,null,null,null,"accepted",Duration.ofSeconds(5),Duration.ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void wrongTargetOrContainerAndUnreadySessionCannotReturnCompleteFacts() throws Exception {
        var source=binding(null,null); var app=source.application("orders",1,Duration.ofSeconds(5));
        var wrongSession=new Session(); wrongSession.target="another-target";
        assertThatThrownBy(() -> new RemoteManagedObserver(source,wrongSession,CLOCK,null)).isInstanceOf(IllegalStateException.class);
        var session=new Session(); session.modify=(tool,body) -> { body.path("data").deepCopy(); ((ObjectNode)body.get("data")).put("containerId","b".repeat(12)); return body; };
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            assertThat(observer.observe(app,Probe.SERVICE).payload().collection().quality()).isEqualTo(Quality.ERROR);
            session.ready=false;
            assertThatThrownBy(() -> observer.observe(app,Probe.SERVICE)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void malformedWrongSourceAndFutureTimesAreRejectedAndStaleTimeIsNotRefreshed() throws Exception {
        var source=binding(null,null); var app=source.application("orders",1,Duration.ofSeconds(5)); var session=new Session();
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            session.modify=(tool,body) -> { body.put("tool","restart_service"); return body; };
            assertThat(observer.observe(app,Probe.SERVICE).payload().collection().quality()).isEqualTo(Quality.ERROR);
            session.modify=(tool,body) -> { body.put("observedAt",NOW.plusSeconds(10).toString()); body.put("collectedAt",NOW.plusSeconds(11).toString()); return body; };
            assertThat(observer.observe(app,Probe.SERVICE).payload().collection().quality()).isEqualTo(Quality.ERROR);
            session.modify=(tool,body) -> { body.put("observedAt",NOW.minusSeconds(100).toString()); body.put("collectedAt",NOW.minusSeconds(99).toString()); return body; };
            var stale=observer.observe(app,Probe.SERVICE);
            assertThat(stale.observedAt()).isEqualTo(NOW.minusSeconds(100));
            assertThat(new DecisionEvidence("old",app.id(),app.version(),stale,stale.observedAt().plus(app.evidenceTtl())).currentAt(NOW)).isFalse();
            session.badJson=true;
            assertThat(observer.observe(app,Probe.SERVICE).payload().collection().quality()).isEqualTo(Quality.ERROR);
        }
    }
    @Test void missingOomIsNotConvertedToFalseAndSourceTruncationSurvives() throws Exception {
        var source=binding(null,null); var app=source.application("orders",1,Duration.ofSeconds(5)); var session=new Session();
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            session.modify=(tool,body) -> { if(tool.equals("container_status")) ((ObjectNode)body.path("data").path("state")).remove("OOMKilled"); return body; };
            var missing=observer.observe(app,Probe.RESOURCES);
            assertThat(missing.payload().collection().quality()).isEqualTo(Quality.MISSING);
            assertThat(missing.payload().resources()).isNull();
            session.modify=(tool,body) -> { ((ObjectNode)body.get("audit")).put("truncated",true); return body; };
            assertThat(observer.observe(app,Probe.RESOURCES).payload().collection().quality()).isEqualTo(Quality.TRUNCATED);
        }
    }
    @Test void endpointBindingAndHttpPredicateExcludeRedirectsAndWrongBusinessBodies() throws Exception {
        var health=endpoint("health","/health",""); var business=endpoint("orders","/orders","accepted");
        var source=binding(health,business); var app=source.application("orders",1,Duration.ofSeconds(5)); var session=new Session();
        session.modify=(tool,body) -> { if(tool.equals("http_probe")) { var data=(ObjectNode)body.get("data"); String name=data.path("endpoint").asText();
            data.put("uri",(name.equals("health") ? health : business).uri().toString()); data.put("body","accepted"); } return body; };
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            assertThat(observer.observe(app,Probe.BUSINESS).status()).isEqualTo(Status.HEALTHY);
            session.modify=(tool,body) -> { var data=(ObjectNode)body.get("data"); data.put("uri",health.uri().toString()).put("statusCode",302).put("body","accepted"); return body; };
            assertThat(observer.observe(app,Probe.HEALTH).status()).isEqualTo(Status.UNHEALTHY);
            session.modify=(tool,body) -> { var data=(ObjectNode)body.get("data"); data.put("uri",business.uri().toString()).put("body","not-ready"); return body; };
            assertThat(observer.observe(app,Probe.BUSINESS).status()).isEqualTo(Status.UNHEALTHY);
            session.modify=(tool,body) -> { ((ObjectNode)body.get("data")).put("uri","http://127.0.0.1:18080/another"); return body; };
            assertThat(observer.observe(app,Probe.BUSINESS).payload().collection().quality()).isEqualTo(Quality.ERROR);
        }
        assertThat(session.calls).allMatch(name -> name.equals("http_probe"));
    }
    @Test void sourceLogsKeepWindowAndAreRedactedAndClippedWithoutChangingIdentity() throws Exception {
        var source=binding(null,null); var app=source.application("orders",1,Duration.ofSeconds(5)); var session=new Session();
        session.modify=(tool,body) -> { ((ObjectNode)body.get("data")).put("text","api_key=sk_test_remote_observer_secret\n"+"line ".repeat(350)); return body; };
        try(var observer=new RemoteManagedObserver(source,session,CLOCK,null)) {
            var logs=observer.observe(app,Probe.LOGS);
            assertThat(logs.detail()).doesNotContain("sk_test_remote_observer_secret").hasSizeLessThanOrEqualTo(1200);
            assertThat(logs.payload().collection().quality()).isEqualTo(Quality.TRUNCATED);
            assertThat(logs.observedAt()).isEqualTo(NOW.minusSeconds(2));
            assertThat(logs.payload().collection().windowStart()).isEqualTo(NOW.minusSeconds(302));
        }
    }
    static final class Session implements OpsReadSession {
        final List<String> calls=new ArrayList<>(); String target="remote-test"; boolean ready=true,closed,badJson;
        BiFunction<String,ObjectNode,ObjectNode> modify=(tool,body) -> body;
        @Override public McpCallResult callTool(String name,ObjectNode args) {
            calls.add(name);
            if(badJson) return McpCallResult.success("{\"tool\":\"first\",\"tool\":\"second\"}",List.of());
            String targetArg=args.path(name.equals("http_probe") ? "endpoint" : "service").asText();
            var body=JSON.createObjectNode().put("tool",name).put("target",targetArg).put("success",true).put("current",true)
                    .put("observedAt",NOW.minusSeconds(1).toString()).put("collectedAt",NOW.toString());
            body.putObject("audit").put("truncated",false);
            var data=body.putObject("data").put("service","order-api").put("containerId",CONTAINER);
            if(name.equals("container_status")) data.putObject("state").put("Status","running").put("Running",true).put("Restarting",false)
                    .put("OOMKilled",false).put("ExitCode",0).put("FinishedAt","0001-01-01T00:00:00Z");
            if(name.equals("container_resources")) data.put("MemUsage","10MiB / 1GiB");
            if(name.equals("http_probe")) data.put("endpoint",targetArg).put("statusCode",200).put("body","accepted");
            if(name.equals("logs")) data.put("since",NOW.minusSeconds(302).toString()).put("until",NOW.minusSeconds(2).toString()).put("text","healthy");
            return McpCallResult.success(modify.apply(name,body).toString(),List.of());
        }
        @Override public boolean isReady() { return ready && !closed; }
        @Override public String targetId() { return target; }
        @Override public void close() { closed=true; }
    }
}
