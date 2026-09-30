package com.clawkit.ops.loop.managed;

import java.util.List;
import java.util.Objects;

/** A proposal is not permission and never asserts that a repair has happened. */
public record OpsDecision(Disposition disposition, String reason, List<String> evidenceRefs,
                          Playbook playbook, List<ManagedObserver.Probe> nextProbes,
                          Integer recheckAfterSeconds) {
    public enum Disposition { INVESTIGATE, WAIT, PROPOSE_ACTION, ESCALATE }
    public enum Playbook {
        START_STOPPED_V1("START_SERVICE"), RESTART_UNHEALTHY_V1("RESTART_SERVICE");
        private final String action;
        Playbook(String action) { this.action = action; }
        public String action() { return action; }
    }

    public OpsDecision {
        Objects.requireNonNull(disposition);
        if (reason == null || reason.isBlank() || reason.length() > 1600)
            throw new IllegalArgumentException("bounded decision reason required");
        evidenceRefs = List.copyOf(Objects.requireNonNull(evidenceRefs));
        nextProbes = List.copyOf(Objects.requireNonNull(nextProbes));
        if (evidenceRefs.size() > 12 || evidenceRefs.stream().distinct().count() != evidenceRefs.size())
            throw new IllegalArgumentException("invalid evidence references");
        if ((disposition == Disposition.PROPOSE_ACTION) != (playbook != null))
            throw new IllegalArgumentException("only a proposal may specify a reviewed playbook");
        boolean scheduled = disposition == Disposition.WAIT || disposition == Disposition.INVESTIGATE;
        if (scheduled != (recheckAfterSeconds != null)
                || (scheduled && (recheckAfterSeconds < 1 || recheckAfterSeconds > 300)))
            throw new IllegalArgumentException("waiting/investigation must have a bounded recheck time");
        if ((disposition == Disposition.INVESTIGATE) != !nextProbes.isEmpty()
                || nextProbes.stream().distinct().count() != nextProbes.size())
            throw new IllegalArgumentException("only investigation specifies nonempty next probes");
        if (disposition != Disposition.ESCALATE && evidenceRefs.isEmpty())
            throw new IllegalArgumentException("decision must cite collected evidence");
    }

    public static OpsDecision systemEscalation(String reason) {
        return new OpsDecision(Disposition.ESCALATE, reason, List.of(), null, List.of(), null);
    }
}
