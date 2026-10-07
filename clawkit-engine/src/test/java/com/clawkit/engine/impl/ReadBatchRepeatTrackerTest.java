package com.clawkit.engine.impl;

import com.clawkit.tools.*;
import com.clawkit.tools.action.*;
import com.clawkit.tools.impl.ReadTool;
import com.clawkit.tools.impl.WriteTool;
import com.clawkit.tools.schema.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ReadBatchRepeatTrackerTest {
    @TempDir Path workspace;
    private static final ObjectMapper JSON = new ObjectMapper();
    private ToolCall call(String id, String path, boolean reversed) {
        var args = JSON.createObjectNode();
        if (reversed) args.put("limit", 100).put("path", path);
        else args.put("path", path).put("limit", 100);
        return new ToolCall(id, "read", args);
    }
    private ToolExecutionResult result(ToolCall call, String text) {
        return ToolExecutionResult.success(call.id(), "read", text, 1, new ReadTool(workspace).metadata());
    }
    private void batch(ReadBatchRepeatTracker tracker, int iteration, String text) {
        var a=call("a-"+iteration,"a.json",iteration%2==0);
        var b=call("b-"+iteration,"b.json",iteration%2!=0);
        tracker.observe(iteration%2==0?List.of(b,a):List.of(a,b), List.of(result(a,text),result(b,"stable")));
    }

    @Test void equivalentPairedReadsAreCountedAcrossCallIdsOrderAndJsonKeyOrder() {
        var tracker=new ReadBatchRepeatTracker();
        batch(tracker,1,"private source fact"); batch(tracker,2,"private source fact");
        assertThat(tracker.takeWarning()).isNull(); batch(tracker,3,"private source fact");
        assertThat(tracker.takeWarning()).startsWith("[Runtime][Repeated Read Batch]")
            .doesNotContain("private source fact","a.json","b.json");
        assertThat(tracker.takeWarning()).isNull();
        for(int i=4;i<100;i++) {batch(tracker,i,"private source fact");assertThat(tracker.takeWarning()).isNull();}
    }

    @Test void changedReturnedTextRevokesPendingWarningAndRestartsCounting() {
        var tracker=new ReadBatchRepeatTracker();
        for(int i=1;i<=3;i++)batch(tracker,i,"revision 1");
        batch(tracker,4,"revision 2"); assertThat(tracker.takeWarning()).isNull();
        batch(tracker,5,"revision 2"); assertThat(tracker.takeWarning()).isNull();
        batch(tracker,6,"revision 2"); assertThat(tracker.takeWarning()).isNotNull();
    }

    @Test void failedUnknownMisattributedRemoteAndWriteResultsCannotCountAsStableNativeReads() {
        var a=call("a","a.json",false);var b=call("b","b.json",false);
        var nativeMetadata=new ReadTool(workspace).metadata();
        var remoteMetadata=new ToolMetadata("read","remote",null,null,nativeMetadata.behavior(),
            nativeMetadata.executionPolicy(),ToolMetadataProvenance.mcp("remote","read",true));
        var writeCall=new ToolCall("b","write",JSON.createObjectNode().put("path","out.json"));
        var writeResult=ToolExecutionResult.success("b","write","written",1,new WriteTool(workspace).metadata())
            .withReliability(EffectCertainty.EFFECT_CONFIRMED,FailureClass.NONE,"attempt");
        var invalid=List.of(
            ToolExecutionResult.error("b","read","DENIED","denied",1,nativeMetadata),
            result(b,"stable").withReliability(EffectCertainty.EFFECT_UNKNOWN,FailureClass.NONE,null),
            result(call("wrong","b.json",false),"stable"),
            ToolExecutionResult.success("b","read","stable",1,remoteMetadata));
        for(var bad:invalid) {
            var tracker=new ReadBatchRepeatTracker();
            for(int i=1;i<=3;i++)batch(tracker,i,"same");
            tracker.observe(List.of(a,b),List.of(result(a,"same"),bad));
            assertThat(tracker.takeWarning()).isNull();
            batch(tracker,4,"same");batch(tracker,5,"same");assertThat(tracker.takeWarning()).isNull();
        }
        var tracker=new ReadBatchRepeatTracker();
        for(int i=1;i<=3;i++)batch(tracker,i,"same");
        tracker.observe(List.of(a,writeCall),List.of(result(a,"same"),writeResult));
        assertThat(tracker.takeWarning()).isNull();
    }

    @Test void rotatingNativeReadGroupsPromptProgressAfterTheFirstRevisitedGroupInALongReadPhase() {
        var tracker=new ReadBatchRepeatTracker();
        for(int i=1;i<=6;i++) {
            var a=call("group-"+i+"-a","group-"+i+"-a.json",false);
            var b=call("group-"+i+"-b","group-"+i+"-b.json",false);
            tracker.observe(List.of(a,b),List.of(result(a,"value "+i),result(b,"stable "+i)));
            assertThat(tracker.takeWarning()).isNull();
        }
        var a=call("revisit-a","group-3-a.json",false);var b=call("revisit-b","group-3-b.json",false);
        tracker.observe(List.of(a,b),List.of(result(a,"value 3"),result(b,"stable 3")));
        assertThat(tracker.takeWarning()).contains("read phase","Required polling and fresh verification remain allowed")
            .doesNotContain("group-3","value 3","stable 3");
        tracker.observe(List.of(a,b),List.of(result(a,"value 3"),result(b,"stable 3")));
        assertThat(tracker.takeWarning()).isNull();
    }

    @Test void changedValuesInARevisitedGroupInvalidateTheWholeReadPhase() {
        var tracker=new ReadBatchRepeatTracker();
        for(int i=1;i<=6;i++)group(tracker,i,"old "+i);
        group(tracker,2,"fresh 2");assertThat(tracker.takeWarning()).isNull();
        for(int i=3;i<=7;i++){group(tracker,i,"old "+i);assertThat(tracker.takeWarning()).isNull();}
        group(tracker,2,"fresh 2");assertThat(tracker.takeWarning()).contains("read phase");
    }
    @Test void evictedGroupsAndAnInterveningWriteDoNotClaimAReadPhaseRevisit() {
        var tracker=new ReadBatchRepeatTracker();
        for(int i=1;i<=13;i++){group(tracker,i,"value "+i);assertThat(tracker.takeWarning()).isNull();}
        group(tracker,1,"value 1");assertThat(tracker.takeWarning()).isNull();
        var write=new ToolCall("write","write",JSON.createObjectNode().put("path","out.json"));
        var written=ToolExecutionResult.success("write","write","written",1,new WriteTool(workspace).metadata())
            .withReliability(EffectCertainty.EFFECT_CONFIRMED,FailureClass.NONE,"attempt");
        tracker.observe(List.of(write),List.of(written));
        group(tracker,2,"value 2");assertThat(tracker.takeWarning()).isNull();
    }
    private void group(ReadBatchRepeatTracker tracker,int group,String text) {
        var a=call("g"+group+"a","g"+group+"a.json",false);var b=call("g"+group+"b","g"+group+"b.json",false);
        tracker.observe(List.of(a,b),List.of(result(a,text),result(b,text)));
    }

    @Test void duplicateIdsIncompleteBatchesAndSingleReadsResetTheSequence() {
        var a=call("same","a.json",false);var b=call("same","b.json",false);
        for(int invalid=0;invalid<3;invalid++) {
            var tracker=new ReadBatchRepeatTracker();
            for(int i=1;i<=3;i++)batch(tracker,i,"same");
            if(invalid==0)tracker.observe(List.of(a,b),List.of(result(a,"same"),result(b,"stable")));
            if(invalid==1)tracker.observe(List.of(a,b),List.of(result(a,"same")));
            if(invalid==2)tracker.observe(List.of(a),List.of(result(a,"same")));
            assertThat(tracker.takeWarning()).isNull();
        }
    }
}
