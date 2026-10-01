package com.clawkit.ops.loop.managed;

import java.util.Objects;

/** Saved logical scope and fingerprints of user configuration; contains no SSH credentials. */
public record RemoteManagedSource(RemoteObservationBinding binding,String descriptorHash,String configurationHash) {
    public RemoteManagedSource {
        Objects.requireNonNull(binding); OpsKnowledge.hash(descriptorHash); OpsKnowledge.hash(configurationHash);
    }
}
