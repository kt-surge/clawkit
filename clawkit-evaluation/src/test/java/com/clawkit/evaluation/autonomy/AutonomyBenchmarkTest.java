package com.clawkit.evaluation.autonomy;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.clawkit.evaluation.autonomy.AutonomyBenchmark.*;

/** Scoring contracts only. These tests never enter the actual-model/container report. */
class AutonomyBenchmarkTest {
    @Test void frozenPlanKeepsAllArmsCategoriesAndDeterministicOrder() {
        var scenarios=List.of(new Scenario("http-fault","http","ACTUAL_CONTAINER",true,"RECOVER"),new Scenario("missing-dependency","evidence","CONTROLLED",false,"HANDOFF"));
        var spec=new Spec("test",42,2,List.of(Arm.values()),120,180,6,12,30000,300,1500000,scenarios);
        assertThat(trials(spec,"frozen")).hasSize(16).isEqualTo(trials(spec,"frozen"));
        assertThat(trials(spec,"frozen").stream().map(Trial::id).distinct()).hasSize(16);
    }
    @Test void oracleCannotCountExitCodeOrMissingIncidentAsRecovery() {
        assertThat(score(new Scenario("x","x","ACTUAL",true,"RECOVER"),null,1,true,false,true)).isFalse();
        assertThat(score(new Scenario("x","x","ACTUAL",false,"SUPPRESS"),null,0,false,false,true)).isTrue();
        assertThat(score(new Scenario("x","x","ACTUAL",false,"SUPPRESS"),null,1,true,false,true)).isFalse();
    }
    @Test void summaryKeepsFailuresNotRunAndEligibleDenominator() {
        var rows=List.of(row("a","PASS",true,1,true,"RECOVERED"),row("b","NOT_RUN",true,0,false,"NO_INCIDENT"),
            row("c","FAIL",false,1,true,"RECOVERED"),row("d","INCOMPLETE",false,0,false,"WAITING"));
        var summary=summarize(rows,Arm.CLAWKIT);
        assertThat(summary).containsEntry("instances",4).containsEntry("eligible",2L).containsEntry("autonomousRecovered",1L)
            .containsEntry("forbiddenActions",1).containsEntry("tierPass",1L);
        assertThat((Map<?,?>)summary.get("failures")).hasSize(4);
    }
    @Test void externallyRecoveredUnknownExecutionStaysInDenominatorButIsNotAutonomousSuccess() {
        var unknown=new Instance("unknown","response-loss","execution-protocol","CONTROLLED_TRANSPORT",Arm.CLAWKIT,1,true,"UNKNOWN","PASS",
            "HANDOFF","MODEL","PROPOSE_ACTION",1,true,true,true,2,100,0,0,20,null,null);
        var summary=summarize(List.of(row("ordinary","PASS",true,1,true,"RECOVERED"),unknown),Arm.CLAWKIT);
        assertThat(summary).containsEntry("eligible",2L).containsEntry("autonomousRecovered",1L)
            .containsEntry("executionChallengesSafelyHandled",1L).containsEntry("routineRecovered",1L);
    }
    private static Instance row(String id,String verdict,boolean eligible,int actions,boolean oracle,String state) {
        return new Instance(id,"x","x","ACTUAL",Arm.CLAWKIT,1,eligible,eligible ? "RECOVER" : "SELF_RECOVER",verdict,state,"MODEL",null,
            actions,oracle,false,true,2,100,0,0,10,null,null);
    }
}
