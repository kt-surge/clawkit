package com.clawkit.ops.loop.managed;

import com.clawkit.ops.mcp.IsolatedComposeClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ManagedRegistrationTest {
    @TempDir Path root;
    final Clock clock=ManagedDecisionTest.CLOCK;
    IsolatedComposeClient.Target target() {
        return new IsolatedComposeClient.Target("default","daemon-1","unix:///var/run/docker.sock",root.resolve("compose.yaml").toString(),"a".repeat(64),
            "clawkit-autonomy-test","demo-api","b".repeat(64),Map.of());
    }
    @Test void registrationDefaultsToAskAndCannotOverwriteAnExistingIdentity() throws Exception {
        var store=new ManagedRegistrationStore(root,clock); var value=store.register(ManagedDecisionTest.app(),target());
        assertThat(value.policy().mode()).isEqualTo(ActionPolicy.Mode.ASK); assertThat(value.review()).isNull();
        assertThat(store.read(value.application().id())).isEqualTo(value);
        assertThatThrownBy(() -> store.register(value.application(),target())).hasMessageContaining("already registered");
        assertThat(store.read(value.application().id())).isEqualTo(value);
    }
    @Test void differentApplicationNamesCannotControlTheSameContainerInOneWorkspace() throws Exception {
        var store=new ManagedRegistrationStore(root,clock); var a=ManagedDecisionTest.app(); store.register(a,target());
        var alias=new ManagedApplication("alias",a.targetId(),a.composeProject(),a.service(),1,true,a.desiredState(),null,
            a.healthUri(),a.businessUri(),a.businessMarker(),a.checkInterval(),a.evidenceTtl());
        assertThatThrownBy(() -> store.register(alias,target())).hasMessageContaining("another application");
        assertThat(store.applications()).containsExactly(a.id());
    }
    @Test void qualifiedPermissionRequiresExplicitScopeReviewAndCanBeRevokedWithoutDeletingEvidence() throws Exception {
        var store=new ManagedRegistrationStore(root,clock); var a=ManagedDecisionTest.app(); store.register(a,target());
        assertThatThrownBy(() -> store.setPolicy(a.id(),ActionPolicy.Mode.LIMITED_AUTO,Set.of(OpsDecision.Playbook.START_STOPPED_V1),Duration.ofMinutes(10),"human",null))
            .hasMessageContaining("human scope review");
        var value=store.setPolicy(a.id(),ActionPolicy.Mode.LIMITED_AUTO,Set.of(OpsDecision.Playbook.START_STOPPED_V1),Duration.ofMinutes(10),"human","disposable stateless fixture scope reviewed");
        assertThat(value.policy().permitsAuto(a,OpsDecision.Playbook.START_STOPPED_V1,clock.instant())).isTrue();
        assertThat(value.policy().permitsAuto(a,OpsDecision.Playbook.RESTART_UNHEALTHY_V1,clock.instant())).isFalse();
        assertThat(value.review().operator()).isEqualTo("human");
        var revoked=store.setPolicy(a.id(),ActionPolicy.Mode.ASK,value.policy().playbooks(),Duration.ofMinutes(10),"human",null);
        assertThat(revoked.policy().version()).isEqualTo(value.policy().version()+1);
        assertThat(revoked.policy().permitsAuto(a,OpsDecision.Playbook.START_STOPPED_V1,clock.instant())).isFalse();
        try (var history=Files.list(store.directory(a.id()))) {
            assertThat(history.filter(p -> p.getFileName().toString().startsWith("policy-")).toList()).hasSize(3);
        }
    }
    @Test void corruptedReviewedRegistrationFailsClosed() throws Exception {
        var store=new ManagedRegistrationStore(root,clock); var a=ManagedDecisionTest.app(); store.register(a,target());
        store.setPolicy(a.id(),ActionPolicy.Mode.LIMITED_AUTO,Set.of(OpsDecision.Playbook.START_STOPPED_V1),Duration.ofMinutes(10),"human","scope reviewed");
        Path file=store.directory(a.id()).resolve("registration.json");
        var node=ManagedContracts.JSON.readTree(file.toFile()); ((com.fasterxml.jackson.databind.node.ObjectNode)node.path("application")).put("stateless",false);
        Files.writeString(file,node.toString());
        assertThatThrownBy(() -> store.read(a.id())).hasMessageContaining("scope review");
    }
    @Test void registrationCannotRaceAnAlreadyRunningController() throws Exception {
        var store=new ManagedRegistrationStore(root,clock); var a=ManagedDecisionTest.app();
        var incidents=new ManagedIncidentStore(store.directory(a.id()).resolve("controller"),a.id());
        try (var lease=incidents.claim()) { assertThat(incidents.processActive()).isTrue(); assertThatThrownBy(() -> store.register(a,target())).hasMessageContaining("lease unavailable"); }
        assertThat(incidents.processActive()).isFalse();
    }
    @Test void remoteRegistrationPersistsSeparateSourceAndCannotGainLocalRepairOrAlertIdentity() throws Exception {
        var store=new ManagedRegistrationStore(root,clock);
        var binding=new RemoteObservationBinding("remote-test","remote-fixture","order-api","c".repeat(12),null,null,null,List.of());
        var app=binding.application("remote-orders",1,Duration.ofSeconds(5));
        var source=new RemoteManagedSource(binding,"a".repeat(64),"b".repeat(64));
        var value=store.registerRemote(app,source);
        assertThat(store.read(app.id())).isEqualTo(value);
        assertThat(value.target()).isNull(); assertThat(value.policy().mode()).isEqualTo(ActionPolicy.Mode.OBSERVE);
        assertThatThrownBy(() -> store.setPolicy(app.id(),ActionPolicy.Mode.ASK,Set.of(OpsDecision.Playbook.RESTART_UNHEALTHY_V1),Duration.ofMinutes(10),"human",null))
            .hasMessageContaining("only observe");
        assertThatThrownBy(() -> ManagedTriggerStore.Binding.from(value)).hasMessageContaining("full server/container");
        assertThatThrownBy(() -> store.registerRemote(binding.application("alias",1,Duration.ofSeconds(5)),source)).hasMessageContaining("already belongs");
        assertThatThrownBy(() -> new ManagedRegistrationStore.Registration(app,ActionPolicy.ask(app,clock.instant().plusSeconds(60)),null,null,source))
            .hasMessageContaining("only observe");
        // Existing local sources remain independently registerable after a remote source has been saved.
        assertThat(store.register(ManagedDecisionTest.app(),target()).target()).isNotNull();
    }
}
