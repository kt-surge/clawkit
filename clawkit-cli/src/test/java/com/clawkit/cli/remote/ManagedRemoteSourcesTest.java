package com.clawkit.cli.remote;

import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.tools.mcp.McpCallResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ManagedRemoteSourcesTest {
    @TempDir Path root;
    RemoteObservationBinding binding() { return new RemoteObservationBinding("remote-test","fixture","order-api","a".repeat(12),null,null,null,List.of()); }
    RemoteTargetRegistration target(String alias,String profile) {
        return new RemoteTargetRegistration(2,"remote-test",new OpenSshAliasReference(alias,"opsro"),profile);
    }
    @Test void everyOpenReloadsConfigurationAndRefusesChangedEndpointOrProfileBeforeSsh() throws Exception {
        Path config=root.resolve("targets.yaml"); var store=new FileRemoteTargetStore(config);
        store.add(target("ssh-test","app-down-readonly-v1"),false); var calls=new AtomicInteger();
        var resolver=new ManagedRemoteSources(() -> new FileRemoteTargetStore(config),(descriptor,connection) -> {
            calls.incrementAndGet(); assertThat(descriptor.targetId()).isEqualTo("remote-test"); return new Session("remote-test",true);
        });
        var source=resolver.resolve(binding()); try(var session=resolver.open(source)) { assertThat(session.isReady()).isTrue(); }
        assertThat(ManagedKnowledgeStore.contentHash(source)).hasSize(64);
        assertThat(source.toString()).doesNotContain("opsro","ssh-test");
        store.add(target("changed-alias","app-down-readonly-v1"),true);
        assertThatThrownBy(() -> resolver.open(source)).hasMessageContaining("configuration or attestation changed");
        store.add(target("ssh-test","postgres-diagnosis-readonly-v1"),true);
        assertThatThrownBy(() -> resolver.open(source)).hasMessageContaining("configuration or attestation changed");
        assertThat(calls).hasValue(1);
    }
    @Test void wrongSessionIdentityIsClosedAndUnknownTargetNeverStartsSsh() throws Exception {
        var store=new FileRemoteTargetStore(root.resolve("targets.yaml")); store.add(target("ssh-test","app-down-readonly-v1"),false);
        var wrong=new Session("another",true); var calls=new AtomicInteger();
        var resolver=new ManagedRemoteSources(() -> store,(descriptor,connection) -> { calls.incrementAndGet(); return wrong; });
        assertThatThrownBy(() -> resolver.open(resolver.resolve(binding()))).hasMessageContaining("READY"); assertThat(wrong.closed).isTrue();
        store.remove("remote-test");
        assertThatThrownBy(() -> resolver.resolve(binding())).hasMessageContaining("not registered"); assertThat(calls).hasValue(1);
    }
    static final class Session implements OpsReadSession {
        final String target; final boolean ready; boolean closed;
        Session(String target,boolean ready) { this.target=target; this.ready=ready; }
        public String targetId() { return target; } public boolean isReady() { return ready && !closed; }
        public McpCallResult callTool(String tool,ObjectNode args) { throw new AssertionError("resolver must not dispatch tools"); }
        public void close() { closed=true; }
    }
}
