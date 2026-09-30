package com.clawkit.ops.loop.managed;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ManagedCommandInboxTest {
    @TempDir Path root;
    @Test void receiptSurvivesRestartAndQueueDoesNotLoseCommandsAfterManyCompletedRequests() throws Exception {
        var queue=new ManagedCommandInbox(root,ManagedDecisionTest.CLOCK);
        for (int i=0;i<110;i++) { var command=queue.enqueue(ManagedCommandInbox.Type.REJECT,incident(),"human"); queue.complete(command,true,"rejected"); }
        var approval=queue.enqueue(ManagedCommandInbox.Type.APPROVE,incident(),"human");
        var reopened=new ManagedCommandInbox(root,ManagedDecisionTest.CLOCK);
        assertThat(reopened.pending()).containsExactly(approval);
        assertThat(approval.expiresAt()).isEqualTo(ManagedDecisionTest.NOW.plusSeconds(300));
        reopened.complete(approval,false,"stale proposal");
        assertThat(reopened.pending()).isEmpty(); assertThat(reopened.receipt(approval.id()).applied()).isFalse();
    }
    @Test void mismatchedCommandFilenameFailsClosed() throws Exception {
        var queue=new ManagedCommandInbox(root,ManagedDecisionTest.CLOCK);
        var command=queue.enqueue(ManagedCommandInbox.Type.APPROVE,incident(),"human");
        Files.move(root.resolve("request-"+command.id()+".json"),root.resolve("request-"+UUID.randomUUID()+".json"));
        assertThatThrownBy(queue::pending).hasMessageContaining("identity differs");
    }
    static ManagedIncident incident() {
        Instant now=ManagedDecisionTest.NOW; var app=ManagedDecisionTest.app();
        var decision=new OpsDecision(OpsDecision.Disposition.PROPOSE_ACTION,"test contract",List.of("ev-test"),OpsDecision.Playbook.START_STOPPED_V1,List.of(),null);
        return new ManagedIncident("inc-queued",app.id(),ManagedContracts.hash(app),ManagedContracts.target(app),ManagedIncident.State.AWAITING_APPROVAL,
            now,now,1,1,null,now.plusSeconds(600),"symptom",List.of(),decision,List.of(),null,null,"test contract");
    }
}
