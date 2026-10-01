package com.clawkit.evaluation.autonomy;

import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.IsolatedComposeClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static com.clawkit.evaluation.autonomy.AutonomyBenchmark.JSON;
import static com.clawkit.evaluation.autonomy.IntelligenceBenchmark.*;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

class IntelligenceBenchmarkTest {
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-10-01T05:00:00Z");
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final ManagedApplication APP=new ManagedApplication("orders","local-isolated","clawkit-autonomy-eval","orders",1,true,
        ManagedApplication.DesiredState.RUNNING,null,URI.create("http://127.0.0.1:18380/health"),URI.create("http://127.0.0.1:18380/business"),"accepted",Duration.ofSeconds(2),Duration.ofSeconds(90));
    @Test void frozenPlanIncludesFailuresAndAllArmsBeforePaidRequests() throws Exception {
        Path repo=Path.of("").toAbsolutePath();
        while(repo!=null && !java.nio.file.Files.exists(repo.resolve("benchmarks/intelligence-autonomy-v1.json"))) repo=repo.getParent();
        assertThat(repo).isNotNull();
        var spec=JSON.readValue(repo.resolve("benchmarks/intelligence-autonomy-v1.json").toFile(),Spec.class);
        assertThat(trials(spec,"frozen")).hasSize(40).isEqualTo(trials(spec,"frozen"));
        assertThat(trials(spec,"frozen").stream().map(Trial::id).distinct()).hasSize(40);
        assertThat(trials(spec,"smoke")).hasSize(8);
        assertThat(trials(spec,"protocol-smoke")).singleElement().satisfies(t -> { assertThat(t.arm()).isEqualTo(Arm.CLAWKIT); assertThat(t.scenario().id()).isEqualTo("oom"); });
        assertThat(spec.scenarios()).filteredOn(s -> s.kind().startsWith("CONTROLLED_")).hasSize(5);
        assertThat(spec.globalProviderCallLimit()).isEqualTo(180); assertThat(spec.globalTokenLimit()).isEqualTo(900000);
    }
    @Test void strongRulesReadAllSourcesAndOomCounterfactBlocksStoppedServiceRepair() throws Exception {
        var probes=new ArrayList<Probe>();
        ManagedObserver observer=(a,p) -> { probes.add(p); return observation(p,true); };
        var initial=ManagedEvidenceCollector.initial(APP,observer,CLOCK);
        var result=rule(APP,observer,initial,CLOCK);
        assertThat(probes).containsAll(Arrays.asList(Probe.values()));
        assertThat(result.decision().disposition()).isEqualTo(OpsDecision.Disposition.ESCALATE);
        assertThat(primary(result.diagnosis())).isEqualTo(DiagnosticReport.Cause.RESOURCE_EXHAUSTION);
        assertThat(result.providerExchanges()).isEmpty();
    }
    @Test void systemHandoffAndReasonableUnknownAreNotRootCauseSuccess() {
        var scenario=new Scenario("bounded","CONTROLLED",DiagnosticReport.Cause.APPLICATION_FAILURE,"HANDOFF",false,true);
        var report=new DiagnosticReport("insufficient evidence",List.of(new DiagnosticReport.Hypothesis("h1",DiagnosticReport.Cause.UNKNOWN,
            DiagnosticReport.Assessment.UNKNOWN,"current sources are incomplete",List.of(),List.of(),List.of("source incomplete"),List.of(),List.of())));
        var system=new OpsDecisionAgent.Outcome(OpsDecisionAgent.Origin.SYSTEM,OpsDecision.systemEscalation("budget exhausted"),List.of(),List.of(),List.of(),"","BUDGET_EXHAUSTED",report);
        assertThat(diagnosisPass(scenario,system)).isFalse(); assertThat(choicePass(scenario,system)).isFalse();
        var model=new OpsDecisionAgent.Outcome(OpsDecisionAgent.Origin.MODEL,OpsDecision.systemEscalation("model cannot establish cause"),List.of(),List.of(),List.of(),"",null,report);
        assertThat(diagnosisPass(scenario,model)).isTrue(); assertThat(primary(model.diagnosis())).isNotEqualTo(scenario.cause());
    }
    @Test void relationOracleUsesFixedPositiveAndIndependentNegativePairs() throws Exception {
        var target=new IsolatedComposeClient.Target("default","test-daemon","unix:///var/run/docker.sock",root.resolve("compose.yaml").toString(),"f".repeat(64),
            APP.composeProject(),"orders","a".repeat(64),Map.of("stock","b".repeat(64)));
        var report=RelationBenchmark.evaluate(root,target,CLOCK);
        assertThat(report).containsEntry("truePositive",1).containsEntry("falsePositive",0).containsEntry("falseNegative",0).containsEntry("ordersDeliveries",2L);
        assertThat(RelationBenchmark.cases()).hasSize(6);
    }
    private static Observation observation(Probe p,boolean oom) {
        Status status=switch(p) { case SERVICE -> Status.STOPPED; case HEALTH,BUSINESS -> Status.UNHEALTHY; case DEPENDENCIES -> Status.HEALTHY; default -> Status.UNKNOWN; };
        var payload=p==Probe.RESOURCES ? new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,NOW,NOW,NOW,"bytes","snapshot","",null),
            new EvidenceEnvelope.ResourceFacts(oom,137,NOW,0L,100L),List.of(),List.of()) : EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,NOW,"");
        return new Observation(APP.targetId(),APP.composeProject(),APP.service(),p,NOW,status,"controlled current fact",payload);
    }
}
