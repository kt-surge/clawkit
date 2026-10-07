package com.clawkit.engine;

import com.clawkit.tools.control.ExecutionControl;
import java.util.Objects;

/**
 * Optional, trusted caller-owned acceptance check for a single ordinary ReAct run.
 * Implementations must be bounded, local and read-only, observe the supplied control,
 * and must not invoke models, grant permissions or consume evaluation Gold.
 * Acceptance covers only the caller's declared conditions, not general task correctness.
 */
@FunctionalInterface
public interface TaskCompletionCheck {
    TaskCompletionCheck NONE = request -> Result.accept();

    Result check(Request request);

    record Request(String runId, int turn, String proposedOutput, ExecutionControl control) {
        public Request {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(control, "control");
            if (turn < 1) throw new IllegalArgumentException("turn must be positive");
            if (proposedOutput == null) proposedOutput = "";
        }
    }

    enum Decision { ACCEPT, RETRY, REJECT }

    record Result(Decision decision, String code, String feedback) {
        public Result {
            Objects.requireNonNull(decision, "decision");
            if (code == null || !code.matches("[A-Z0-9_]{1,64}"))
                throw new IllegalArgumentException("bounded acceptance code required");
            if (feedback == null) feedback = "";
            if (feedback.length() > 2048)
                throw new IllegalArgumentException("acceptance feedback exceeds 2048 characters");
        }
        public static Result accept() { return new Result(Decision.ACCEPT, "ACCEPTED", ""); }
        public static Result retry(String code, String feedback) { return new Result(Decision.RETRY, code, feedback); }
        public static Result reject(String code, String feedback) { return new Result(Decision.REJECT, code, feedback); }
    }
}
