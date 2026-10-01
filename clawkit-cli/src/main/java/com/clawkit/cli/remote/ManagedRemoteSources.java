package com.clawkit.cli.remote;

import com.clawkit.ops.delivery.RemoteMcpSessionAdapter;
import com.clawkit.ops.delivery.managed.ManagedOperationsService;
import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.tools.remote.RemoteMcpSession;
import com.clawkit.tools.remote.RemoteSshConnectionSpec;
import com.clawkit.tools.remote.RemoteTargetDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Supplier;

/** CLI owns credential/configuration resolution; the controller receives only attested read sessions. */
public final class ManagedRemoteSources implements ManagedOperationsService.RemoteSources {
    @FunctionalInterface interface SessionOpener {
        OpsReadSession open(RemoteTargetDescriptor descriptor,RemoteSshConnectionSpec connection) throws Exception;
    }
    private record Resolved(RemoteManagedSource source,RemoteTargetDescriptor descriptor,RemoteSshConnectionSpec connection) {}
    private final Supplier<RemoteTargetStore> stores;
    private final SessionOpener opener;
    public ManagedRemoteSources(Path configuration) {
        this(() -> new FileRemoteTargetStore(configuration),(descriptor,connection) -> {
            var session=new RemoteMcpSession(descriptor,connection);
            try { session.start(); return new RemoteMcpSessionAdapter(session); }
            catch(Exception e) { session.close(); throw e; }
        });
    }
    ManagedRemoteSources(Supplier<RemoteTargetStore> stores,SessionOpener opener) {
        this.stores=stores; this.opener=opener;
    }
    private Resolved resolved(RemoteObservationBinding binding) throws IOException {
        var store=stores.get(); var registration=store.getRegistration(binding.targetId());
        Object config; RemoteTargetDescriptor descriptor; RemoteSshConnectionSpec connection;
        if (registration.isPresent()) {
            config=registration.get(); descriptor=RemoteTargetResolver.resolveDescriptor(registration.get());
            connection=RemoteTargetResolver.resolveConnectionSpec(registration.get());
        } else {
            var legacy=store.get(binding.targetId()).orElseThrow(() -> new IOException("remote target is not registered"));
            config=legacy; descriptor=RemoteTargetResolver.resolveLegacyDescriptor(legacy);
            connection=RemoteTargetResolver.resolveLegacyConnectionSpec(legacy);
        }
        return new Resolved(new RemoteManagedSource(binding,ManagedKnowledgeStore.contentHash(descriptor),
            ManagedKnowledgeStore.contentHash(config)),descriptor,connection);
    }
    @Override public RemoteManagedSource resolve(RemoteObservationBinding binding) throws IOException { return resolved(binding).source(); }
    @Override public OpsReadSession open(RemoteManagedSource source) throws Exception {
        var current=resolved(source.binding());
        if (!current.source().equals(source)) throw new IOException("remote configuration or attestation changed; review and register a new application identity");
        var session=opener.open(current.descriptor(),current.connection());
        if (!session.isReady() || !source.binding().targetId().equals(session.targetId())) {
            session.close(); throw new IOException("remote source did not reach an attested READY state");
        }
        return session;
    }
}
