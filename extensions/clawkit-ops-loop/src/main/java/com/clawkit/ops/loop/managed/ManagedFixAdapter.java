package com.clawkit.ops.loop.managed;

import com.clawkit.tools.action.EffectCertainty;
import java.util.Objects;

/** Trusted transport accepts registered identity and reviewed action only; no arbitrary command string. */
@FunctionalInterface
public interface ManagedFixAdapter {
    ExecutionReport execute(ManagedApplication application, OpsDecision.Playbook playbook) throws Exception;
    record ExecutionReport(EffectCertainty certainty, String detail) {
        public ExecutionReport {
            Objects.requireNonNull(certainty);
            if (detail == null || detail.length() > 1200) throw new IllegalArgumentException("bounded execution detail required");
        }
    }
}
