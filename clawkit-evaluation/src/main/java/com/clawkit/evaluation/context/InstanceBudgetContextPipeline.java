package com.clawkit.evaluation.context;

import com.clawkit.context.*;
import com.clawkit.provider.ModelRequest;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.clawkit.tools.schema.ToolDefinition;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Shared instance budget, with an optional planning ceiling derived from the existing dispatch reserve. */
public final class InstanceBudgetContextPipeline implements ContextPipeline {
    public record InputPlan(int turn,long availableBefore,long reserveBefore,long fixedReserve,
                            int nativeMessageTokens,long planningCapacityTokens,long availableAfter,
                            long reserveAfter,boolean narrowed,boolean fitsAfter,String outcome) {}
    private final ContextPipeline delegate;
    private final ExecutionControl instance;
    private final Tokenizer tokenizer;
    private final ProviderRequestLimitGateway limits;
    private final Consumer<InputPlan> recorder;
    private List<ToolDefinition> tools=List.of();
    private boolean built;

    public InstanceBudgetContextPipeline(ContextPipeline delegate,ExecutionControl instance) {
        this.delegate=Objects.requireNonNull(delegate);this.instance=Objects.requireNonNull(instance);
        this.tokenizer=null;this.limits=null;this.recorder=null;
    }
    /** Trusted evaluation composition; the provider's hard check and actual budget remain authoritative. */
    public InstanceBudgetContextPipeline(ContextPipeline delegate,ExecutionControl instance,Tokenizer tokenizer,
                                        ProviderRequestLimitGateway limits,Consumer<InputPlan> recorder) {
        this.delegate=Objects.requireNonNull(delegate);this.instance=Objects.requireNonNull(instance);
        this.tokenizer=Objects.requireNonNull(tokenizer);this.limits=Objects.requireNonNull(limits);
        this.recorder=Objects.requireNonNull(recorder);
    }
    @Override public ModelContext build(ContextRequest request) {
        tools=request.tools()==null?List.of():List.copyOf(request.tools());built=true;
        return delegate.build(request);
    }
    @Override public CompactionResult compact(CompactionRequest request) {
        instance.checkpoint();
        long available=Math.min(request.runTokenBudgetRemaining(),instance.tokenBudget().remaining());
        if(limits==null)return delegate.compact(withCapacity(request,available));
        if(!built)throw new IllegalStateException("input reserve planning requires the current build tool surface");
        long before=limits.conservativeReserve(ModelRequest.of(request.modelContext(),tools));
        long fixed=limits.conservativeReserve(ModelRequest.of(List.of(),tools));
        int messageTokens=tokenizer.countTokens(request.modelContext());
        if(available<fixed) {
            recorder.accept(new InputPlan(request.turnCount(),available,before,fixed,messageTokens,0,available,before,
                false,false,"FIXED_RESERVE_EXCEEDS_REMAINING"));
            throw new ExecutionHaltedException(ExecutionHaltedException.Reason.BUDGET_EXHAUSTED,
                "insufficient fixed request reserve: "+fixed);
        }
        long capacity=available;
        boolean narrowed=before>available;
        if(narrowed) {
            long variableBytes=Math.max(1,before-fixed);
            long messageAllowance=(long)Math.floor((double)messageTokens*(available-fixed)/variableBytes);
            // This is a smaller compaction planning ceiling, never a replacement for the real ledger.
            capacity=Math.min(available,Math.max(1,messageAllowance+request.toolDefTokens()
                +(long)request.reservedOutputTokens()+request.safetyMarginTokens()));
        }
        CompactionResult result=delegate.compact(withCapacity(request,capacity));
        long after=limits.conservativeReserve(ModelRequest.of(result.messages(),tools));
        long availableAfter=Math.min(request.runTokenBudgetRemaining(),instance.tokenBudget().remaining());
        recorder.accept(new InputPlan(request.turnCount(),available,before,fixed,messageTokens,capacity,availableAfter,after,
            narrowed,after<=availableAfter,result.audit()!=null&&result.audit().failed()?"COMPACTION_FAILED":
                narrowed?"NARROWED_INPUT_CAPACITY":"UNCHANGED_INPUT_CAPACITY"));
        return result;
    }
    private static CompactionRequest withCapacity(CompactionRequest request,long capacity) {
        return new CompactionRequest(request.modelContext(),request.toolDefTokens(),request.turnCount(),request.hint(),
            request.reservedOutputTokens(),request.safetyMarginTokens(),capacity);
    }
}
