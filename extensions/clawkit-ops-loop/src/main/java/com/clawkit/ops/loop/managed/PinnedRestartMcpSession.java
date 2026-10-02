package com.clawkit.ops.loop.managed;

import com.clawkit.ops.mcp.*;
import com.clawkit.tools.remote.*;
import java.io.IOException;
import java.time.Clock;
import java.util.Set;

/** Thin private V2 adapter over the same OpenSSH transport and strict MCP attestation. */
public final class PinnedRestartMcpSession implements PinnedRestartAdapter.Session {
    private final RemoteMcpSession session;
    private PinnedRestartMcpSession(RemoteMcpSession session) { this.session=session; }
    public static PinnedRestartMcpSession fromReady(RemoteMcpSession session) {
        if(!session.isReady() || session.attestationSnapshot()==null
                || !"clawkit-ops-fix-v2".equals(session.attestationSnapshot().serverName())
                || !"2".equals(session.attestationSnapshot().probeVersion())
                || !OpsCapabilityProfile.PINNED_RESTART_V2.name().equals(session.attestationSnapshot().capabilityProfile())
                || !OpsMcpServer.computeExpectedToolContractHash(OpsCapabilityProfile.PINNED_RESTART_V2)
                    .equals(ToolContractHash.computeFromMcpTools(session.attestedTools())))
            throw new IllegalArgumentException("strict pinned V2 READY session required");
        return new PinnedRestartMcpSession(session);
    }
    public static RemoteTargetDescriptor descriptor(String targetId) {
        return new RemoteTargetDescriptor(targetId,"clawkit-ops-fix-v2",OpsMcpServer.PROTOCOL_VERSION,"2",OpsCapabilityProfile.PINNED_RESTART_V2.name(),
            OpsMcpServer.computeToolSetHash(OpsCapabilityProfile.PINNED_RESTART_V2),OpsMcpServer.computeExpectedToolContractHash(OpsCapabilityProfile.PINNED_RESTART_V2));
    }
    public static PinnedRestartMcpSession open(String targetId,RemoteSshConnectionSpec connection) throws Exception {
        var session=new RemoteMcpSession(descriptor(targetId),connection,Clock.systemUTC(),Set.of("restart_pinned"));
        try { session.start(); return fromReady(session); }
        catch(Exception e) { session.close(); throw e; }
    }
    @Override public PinnedRestartContract.Configuration scope() throws Exception {
        return read("repair_scope",PinnedRestartContract.JSON.createObjectNode(),PinnedRestartContract.Configuration.class);
    }
    @Override public PinnedRestartContract.Receipt restart(PinnedRestartContract.Request request) throws Exception {
        return read("restart_pinned",PinnedRestartContract.JSON.valueToTree(request),PinnedRestartContract.Receipt.class);
    }
    @Override public PinnedRestartContract.Receipt receipt(String requestId) throws Exception {
        PinnedRestartContract.requestId(requestId);
        return read("repair_receipt",PinnedRestartContract.JSON.createObjectNode().put("requestId",requestId),PinnedRestartContract.Receipt.class);
    }
    private <T> T read(String tool,com.fasterxml.jackson.databind.node.ObjectNode arguments,Class<T> type) throws Exception {
        var result=session.callTool(tool,arguments);
        if(result.text()==null || result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32768) throw new IOException("bounded V2 reply required");
        var frame=PinnedRestartContract.JSON.readTree(result.text());
        if(!tool.equals(frame.path("tool").asText()) || !"order-api".equals(frame.path("target").asText())
                || !frame.path("current").isBoolean() || !frame.path("current").asBoolean() || !frame.path("data").isObject()
                || !frame.path("success").isBoolean() || !frame.path("audit").path("truncated").isBoolean()
                || frame.path("audit").path("truncated").asBoolean())
            throw new IOException("V2 receipt source contract differs");
        var observed=java.time.Instant.parse(frame.path("observedAt").asText());
        var collected=java.time.Instant.parse(frame.path("collectedAt").asText()); var now=java.time.Instant.now();
        if(observed.isAfter(collected) || collected.isAfter(now.plusSeconds(5)) || observed.isBefore(now.minusSeconds(90)))
            throw new IOException("V2 response collection window invalid");
        T value=PinnedRestartContract.JSON.treeToValue(frame.get("data"),type);
        boolean expectedSuccess=!tool.equals("restart_pinned") || ((PinnedRestartContract.Receipt)value).status()==PinnedRestartContract.Status.DISPATCH_REPORTED;
        if(frame.path("success").asBoolean()!=expectedSuccess || result.isError()==expectedSuccess) throw new IOException("V2 result/status disagree");
        return value;
    }
    @Override public void close() { session.close(); }
}
