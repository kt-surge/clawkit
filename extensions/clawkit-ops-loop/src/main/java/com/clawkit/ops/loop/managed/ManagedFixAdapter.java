package com.clawkit.ops.loop.managed;

import com.clawkit.tools.action.EffectCertainty;
import java.util.Objects;

/** Trusted transport accepts registered identity and reviewed action only; no arbitrary command string. */
@FunctionalInterface
public interface ManagedFixAdapter {
    ExecutionReport execute(ManagedApplication application, OpsDecision.Playbook playbook) throws Exception;
    /** The executor creates this after authorization is persisted; model tool arguments cannot choose it. */
    record Dispatch(String incidentId,String requestId) {
        public Dispatch { Objects.requireNonNull(incidentId); Objects.requireNonNull(requestId); }
    }
    default ExecutionReport execute(ManagedApplication application,OpsDecision.Playbook playbook,Dispatch dispatch) throws Exception {
        return execute(application,playbook);
    }
    record ExecutionReport(EffectCertainty certainty, String detail) {
        public ExecutionReport {
            Objects.requireNonNull(certainty);
            if (detail == null || detail.length() > 1200) throw new IllegalArgumentException("bounded execution detail required");
        }
    }
}
