package com.clawkit.ops.loop.managed;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Human consent and a user-configured qualified policy have distinct provenance. Neither comes from model text. */
public sealed interface RepairAuthorization permits RepairAuthorization.Human, RepairAuthorization.Policy {
    Binding binding();
    record Binding(String incidentId, String applicationHash, OpsDecision.Playbook playbook,
                   String snapshotHash, Instant issuedAt, Instant expiresAt) {
        public Binding {
            ManagedApplication.identifier(incidentId);
            Objects.requireNonNull(applicationHash); Objects.requireNonNull(playbook);
            Objects.requireNonNull(snapshotHash); Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            if (!issuedAt.isBefore(expiresAt)) throw new IllegalArgumentException("authorization must expire after issuance");
        }
        boolean matches(String incidentId, ManagedApplication app, OpsDecision.Playbook playbook,
                        List<DecisionEvidence> fresh, Instant now) {
            return this.incidentId.equals(incidentId) && applicationHash.equals(ManagedContracts.hash(app))
                && this.playbook == playbook && snapshotHash.equals(ManagedContracts.snapshot(fresh))
                && !now.isBefore(issuedAt) && now.isBefore(expiresAt);
        }
    }
    record Human(Binding binding, String operator) implements RepairAuthorization, Request {
        public Human {
            Objects.requireNonNull(binding);
            if (operator == null || operator.isBlank() || operator.length() > 100)
                throw new IllegalArgumentException("human operator required");
        }
    }
    record Policy(Binding binding, String policyHash, long policyVersion) implements RepairAuthorization {
        public Policy { Objects.requireNonNull(binding); Objects.requireNonNull(policyHash); }
    }
    sealed interface Request permits Human, Automatic {}
    enum Automatic implements Request { INSTANCE }

    /** Called by the UI only after an explicit approval, never by an Agent tool. */
    static Human approved(String incidentId, ManagedApplication app, OpsDecision decision,
                          List<DecisionEvidence> evidence, String operator, Clock clock) {
        DecisionEvidenceLedger.validateCollected(app,decision,evidence,clock);
        if (decision.disposition() != OpsDecision.Disposition.PROPOSE_ACTION)
            throw new IllegalArgumentException("a human approval requires a valid action proposal");
        Instant expiry = evidence.stream().map(DecisionEvidence::validUntil).min(Instant::compareTo).orElseThrow();
        if (expiry.isAfter(clock.instant().plusSeconds(300))) expiry = clock.instant().plusSeconds(300);
        return new Human(new Binding(incidentId,ManagedContracts.hash(app),decision.playbook(),
            ManagedContracts.snapshot(evidence),clock.instant(),expiry),operator);
    }
}
