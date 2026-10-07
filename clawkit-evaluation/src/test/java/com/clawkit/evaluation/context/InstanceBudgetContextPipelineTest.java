package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.context.impl.*;
import com.clawkit.engine.*;
import com.clawkit.provider.*;
import com.clawkit.reliability.*;
import com.clawkit.tools.schema.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InstanceBudgetContextPipelineTest {
    private final Tokenizer tokenizer=TokenizerFactory.create("cl100k_base");
    private final ContextBudgetPolicy policy=ContextBudgetPolicy.of(16_384);
    private final ObjectMapper json=new ObjectMapper();
    private final List<InstanceBudgetContextPipeline.InputPlan> plans=new ArrayList<>();
    private final List<ModelRequest> dispatches=new ArrayList<>();
    private final ProviderRequestLimitGateway limit=new ProviderRequestLimitGateway(new ProviderGateway() {
        @Override public ModelResponse generate(ModelRequest request,RunScope scope) {
            dispatches.add(request); return ModelResponse.text("received",new TokenUsage(10,5,15));
        }
        @Override public ModelResponse generateStream(ModelRequest request,RunScope scope,StreamObserver observer) {return generate(request,scope);}
    },2_048,1_024,true);
    private ContextPipeline production() {
        return new DefaultContextPipeline(new LadderedCompactor(null,tokenizer),new ContextBudgetAnalyzer(tokenizer,policy),tokenizer,policy);
    }
    private List<Message> history() {
        var messages=new ArrayList<Message>();messages.add(Message.user("Finish the requested preview using current source values."));
        for(int i=1;i<=8;i++) {
            var call=new ToolCall("source-"+i,"read",json.createObjectNode().put("path","source-"+i+".json"));
            messages.add(Message.assistantWithTools("Inspect source "+i,List.of(call),null));
            messages.add(Message.toolResult(call.id(),("observed synthetic value "+i+" ").repeat(150)));
        }
        return messages;
    }
    private List<ToolDefinition> tools() {return List.of(new ToolDefinition("read","Read bounded source text",json.createObjectNode().put("type","object")));}
    private int toolTokens() {var tool=tools().getFirst();return tokenizer.countTokens(tool.inputSchema().toString())+tokenizer.countTokens(tool.name())+tokenizer.countTokens(tool.description());}
    @Test void plansForTheSameConservativeReserveEvenWhenActualRemainingExceedsTheModelWindow() {
        var control=CancellationTree.root(null,BudgetLedger.of(18_000));
        var pipeline=new InstanceBudgetContextPipeline(production(),control,tokenizer,limit,plans::add);
        var built=pipeline.build(new ContextRequest("Respect the requested preview scope.",history(),List.of(),List.of(),List.of(),List.of(),tools(),policy));
        assertThat(built.budgetReport().status()).isEqualTo(ContextBudgetReport.BudgetStatus.OK);
        assertThat(control.tokenBudget().remaining()).isGreaterThan(policy.contextWindow());
        assertThat(limit.conservativeReserve(ModelRequest.of(built.messages(),tools()))).isGreaterThan(control.tokenBudget().remaining());
        var result=pipeline.compact(new CompactionRequest(built.messages(),toolTokens(),9,CompactionHint.GENERAL,2_048,81,Long.MAX_VALUE));
        assertThat(result.audit().failed()).isFalse();
        assertThat(limit.conservativeReserve(ModelRequest.of(result.messages(),tools()))).isLessThanOrEqualTo(control.tokenBudget().remaining());
        assertThat(plans).singleElement().satisfies(plan->{assertThat(plan.narrowed()).isTrue();assertThat(plan.availableBefore()).isEqualTo(18_000);assertThat(plan.planningCapacityTokens()).isLessThan(plan.availableBefore());assertThat(plan.fitsAfter()).isTrue();});
        assertThat(result.messages()).contains(built.messages().stream().filter(m->m.role()==Role.USER).findFirst().orElseThrow());
        for(int i=6;i<=8;i++){String id="source-"+i;assertThat(result.messages()).contains(built.messages().stream().filter(m->id.equals(m.toolCallId())).findFirst().orElseThrow());}
        assertThat(dispatches).isEmpty();
        assertThat(control.tokenBudget().remaining()).isEqualTo(18_000);
        limit.generate(ModelRequest.of(result.messages(),tools()),new RunScope("task",null,9,RunPhase.REACT,ExecutionMode.REACT,control));
        assertThat(dispatches).hasSize(1);
    }
    @Test void ampleBudgetAndLegacyConstructionPreserveTheOrdinaryCompactionPath() {
        var control=CancellationTree.root(null,BudgetLedger.of(100_000));
        var planned=new InstanceBudgetContextPipeline(production(),control,tokenizer,limit,plans::add);
        var original=new InstanceBudgetContextPipeline(production(),control);
        var buildRequest=new ContextRequest("Respect scope.",history(),List.of(),List.of(),List.of(),List.of(),tools(),policy);
        var built=planned.build(buildRequest);original.build(buildRequest);
        var request=new CompactionRequest(built.messages(),toolTokens(),9,CompactionHint.GENERAL,2_048,81,100_000);
        assertThat(planned.compact(request).messages()).isEqualTo(original.compact(request).messages());
        assertThat(plans).singleElement().satisfies(p->{assertThat(p.narrowed()).isFalse();assertThat(p.planningCapacityTokens()).isEqualTo(100_000);});
        assertThat(dispatches).isEmpty();assertThat(control.tokenBudget().remaining()).isEqualTo(100_000);
    }
    @Test void aChangedToolSurfaceCannotReuseAFormerSmallerFixedReserve() {
        var control=CancellationTree.root(null,BudgetLedger.of(18_000));
        var pipeline=new InstanceBudgetContextPipeline(production(),control,tokenizer,limit,plans::add);
        var small=pipeline.build(new ContextRequest("scope",List.of(Message.user("finish")),List.of(),List.of(),List.of(),List.of(),tools(),policy));
        pipeline.compact(new CompactionRequest(small.messages(),toolTokens(),1,CompactionHint.GENERAL,2_048,81,18_000));
        var largeTools=List.of(new ToolDefinition("read","large declared schema ".repeat(2_000),json.createObjectNode().put("type","object")));
        var large=pipeline.build(new ContextRequest("scope",List.of(Message.user("finish")),List.of(),List.of(),List.of(),List.of(),largeTools,policy));
        assertThatThrownBy(()->pipeline.compact(new CompactionRequest(large.messages(),10_000,1,CompactionHint.GENERAL,2_048,81,18_000)))
            .isInstanceOf(com.clawkit.tools.control.ExecutionHaltedException.class).hasMessageContaining("fixed request reserve");
        assertThat(plans.getLast().outcome()).isEqualTo("FIXED_RESERVE_EXCEEDS_REMAINING");
        assertThat(plans.getLast().fixedReserve()).isGreaterThan(18_000);
        assertThat(dispatches).isEmpty();assertThat(control.tokenBudget().remaining()).isEqualTo(18_000);
    }
    @Test void parentBudgetChangesAfterPlanningAreStillRejectedAtDispatch() {
        var parent=BudgetLedger.of(20_000);var child=parent.childCapped(18_000);
        var control=CancellationTree.root(null,child);
        var pipeline=new InstanceBudgetContextPipeline(production(),control,tokenizer,limit,plans::add);
        var built=pipeline.build(new ContextRequest("scope",history(),List.of(),List.of(),List.of(),List.of(),tools(),policy));
        var result=pipeline.compact(new CompactionRequest(built.messages(),toolTokens(),9,CompactionHint.GENERAL,2_048,81,Long.MAX_VALUE));
        assertThat(plans.getLast().fitsAfter()).isTrue();
        parent.reserveUpTo(17_000);
        assertThatThrownBy(()->limit.generate(ModelRequest.of(result.messages(),tools()),new RunScope("task",null,9,RunPhase.REACT,ExecutionMode.REACT,control)))
            .isInstanceOf(com.clawkit.tools.control.ExecutionHaltedException.class);
        assertThat(dispatches).isEmpty();assertThat(child.remaining()).isEqualTo(3_000);assertThat(parent.remaining()).isEqualTo(3_000);
    }
    @Test void requiredConstraintsAndCancellationAreNotOverriddenByThePlanningCeiling() {
        var control=CancellationTree.root(null,BudgetLedger.of(18_000));
        var pipeline=new InstanceBudgetContextPipeline(production(),control,tokenizer,limit,plans::add);
        var built=pipeline.build(new ContextRequest("scope",history(),List.of(),List.of(),List.of(),List.of(),tools(),policy));
        var anchors=java.util.stream.IntStream.range(0,16).mapToObj(i->new CompactionAnchor("required-"+i,AnchorKind.USER_CONSTRAINT,"must obey this exact condition ".repeat(16),null,true,
            CompactionAnchor.CONFIRMED,AnchorProvenance.USER,java.time.Instant.EPOCH)).toList();
        var result=pipeline.compact(new CompactionRequest(built.messages(),toolTokens(),9,new CompactionHint(CompactionProfile.GENERAL,anchors),2_048,81,Long.MAX_VALUE));
        assertThat(result.audit().failureCode()).isEqualTo("REQUIRED_ANCHORS_OVER_BUDGET");
        assertThat(plans.getLast().outcome()).isEqualTo("COMPACTION_FAILED");
        int recorded=plans.size();control.cancel();
        assertThatThrownBy(()->pipeline.compact(new CompactionRequest(built.messages(),toolTokens(),9)))
            .isInstanceOf(com.clawkit.tools.control.ExecutionHaltedException.class);
        assertThat(plans).hasSize(recorded);assertThat(dispatches).isEmpty();assertThat(control.tokenBudget().remaining()).isEqualTo(18_000);
    }

}
