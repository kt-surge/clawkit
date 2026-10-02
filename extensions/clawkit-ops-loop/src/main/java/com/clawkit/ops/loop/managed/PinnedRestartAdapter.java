package com.clawkit.ops.loop.managed;

import com.clawkit.ops.mcp.PinnedRestartContract;
import com.clawkit.tools.action.EffectCertainty;
import java.time.Clock;
import java.util.Objects;

/** Private transport adapter. It receives the original executor Attempt, never an agent-generated retry id. */
public final class PinnedRestartAdapter implements ManagedFixAdapter {
    public interface Session extends AutoCloseable {
        PinnedRestartContract.Configuration scope() throws Exception;
        PinnedRestartContract.Receipt restart(PinnedRestartContract.Request request) throws Exception;
        PinnedRestartContract.Receipt receipt(String requestId) throws Exception;
    }
    @FunctionalInterface public interface Sessions { Session open() throws Exception; }
    private final ManagedApplication application;
    private final PinnedRestartContract.Configuration expected;
    private final Sessions sessions;
    private final Clock clock;
    public PinnedRestartAdapter(ManagedApplication application,PinnedRestartContract.Configuration expected,Sessions sessions,Clock clock) {
        this.application=Objects.requireNonNull(application); this.expected=Objects.requireNonNull(expected);
        this.sessions=Objects.requireNonNull(sessions); this.clock=Objects.requireNonNull(clock);
        if(!application.stateless() || !application.service().equals(expected.target().service())
                || !application.composeProject().equals(expected.target().project()))
            throw new IllegalArgumentException("reviewed application and fixed restart target differ");
    }
    @Override public ExecutionReport execute(ManagedApplication app,OpsDecision.Playbook playbook) {
        return new ExecutionReport(EffectCertainty.NOT_DISPATCHED,"original executor Attempt and incident required");
    }
    @Override public ExecutionReport execute(ManagedApplication app,OpsDecision.Playbook playbook,Dispatch dispatch) {
        boolean sent=false;
        if(!application.equals(app) || playbook!=OpsDecision.Playbook.RESTART_UNHEALTHY_V1 || !app.repairIntendedAt(clock.instant()))
            return new ExecutionReport(EffectCertainty.NOT_DISPATCHED,"fixed restart application/action/intent differs");
        var request=PinnedRestartContract.Request.of(dispatch.requestId(),dispatch.incidentId(),expected);
        try(var session=sessions.open()) {
            var live=session.scope();
            if(!expected.equals(live) || !live.activeAt(clock.instant()))
                return new ExecutionReport(EffectCertainty.NOT_DISPATCHED,"operator grant/configuration changed or expired");
            sent=true;
            var receipt=session.restart(request);
            if(receipt==null || !request.equals(receipt.request()) || !request.fingerprint().equals(receipt.requestHash())
                    || receipt.at().isAfter(clock.instant().plusSeconds(5)))
                return new ExecutionReport(EffectCertainty.EFFECT_UNKNOWN,"receipt does not bind the original request; human reconciliation required");
            return new ExecutionReport(switch(receipt.status()) {
                case DISPATCH_REPORTED -> EffectCertainty.EFFECT_CONFIRMED;
                case REJECTED -> EffectCertainty.NO_EFFECT_CONFIRMED;
                case UNKNOWN -> EffectCertainty.EFFECT_UNKNOWN;
            },"pinned server receipt: "+receipt.status()+" / "+receipt.code()+"; recovery requires independent reads");
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ExecutionReport(sent ? EffectCertainty.EFFECT_UNKNOWN : EffectCertainty.NOT_DISPATCHED,
                sent ? "pinned reply unavailable; preserve original Attempt and do not redispatch" : "pinned session/scope unavailable; no write request sent");
        }
    }
}
