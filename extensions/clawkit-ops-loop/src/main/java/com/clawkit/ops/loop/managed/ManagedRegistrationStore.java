package com.clawkit.ops.loop.managed;

import com.clawkit.ops.mcp.IsolatedComposeClient;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Local user configuration. Model decisions never receive a reference to this store. */
public final class ManagedRegistrationStore {
    public record ScopeReview(String operator,String note,Instant at,String applicationHash,String targetHash) {
        public ScopeReview {
            if (operator==null || operator.isBlank() || operator.length()>100 || note==null || note.isBlank() || note.length()>500)
                throw new IllegalArgumentException("explicit human scope review required");
            Objects.requireNonNull(at); Objects.requireNonNull(applicationHash); Objects.requireNonNull(targetHash);
        }
    }
    public record Registration(ManagedApplication application,ActionPolicy policy,IsolatedComposeClient.Target target,ScopeReview review,
                               RemoteManagedSource remote) {
        public Registration(ManagedApplication application,ActionPolicy policy,IsolatedComposeClient.Target target,ScopeReview review) {
            this(application,policy,target,review,null);
        }
        public Registration {
            Objects.requireNonNull(application); Objects.requireNonNull(policy);
            if ((target==null)==(remote==null)) throw new IllegalArgumentException("exactly one registered observation source required");
            if (!application.id().equals(policy.applicationId()) || application.version()!=policy.applicationVersion())
                throw new IllegalArgumentException("application, target and permission versions differ");
            if (remote!=null) {
                remote.binding().validate(application);
                if (policy.mode()!=ActionPolicy.Mode.OBSERVE || review!=null || policy.qualification()!=ActionPolicy.Qualification.DRAFT)
                    throw new IllegalArgumentException("remote observation has no qualified repair contract; only observe is supported");
            } else if (!application.composeProject().equals(target.project()) || !application.service().equals(target.service()))
                throw new IllegalArgumentException("application and local target differ");
            if (policy.mode()==ActionPolicy.Mode.LIMITED_AUTO && policy.qualification()==ActionPolicy.Qualification.QUALIFIED
                    && (review==null || !review.applicationHash().equals(ManagedContracts.hash(application))
                        || !review.targetHash().equals(ManagedContracts.hash(target))))
                throw new IllegalArgumentException("qualified user policy requires a matching scope review");
        }
    }
    private final Path root;
    private final Clock clock;
    private final ManagedControlStore registry;
    public ManagedRegistrationStore(Path root,Clock clock) throws IOException {
        this.root=root.toAbsolutePath().normalize(); this.clock=Objects.requireNonNull(clock); registry=new ManagedControlStore(this.root);
    }
    public Path directory(String applicationId) { return root.resolve(ManagedApplication.identifier(applicationId)); }
    public Registration read(String applicationId) throws IOException {
        Path file=directory(applicationId).resolve("registration.json");
        if (!Files.exists(file)) throw new IOException("application is not registered");
        if (Files.size(file)>65_536) throw new IOException("registration exceeds size limit");
        Registration value=ManagedContracts.JSON.readValue(file.toFile(),Registration.class);
        if (!applicationId.equals(value.application().id())) throw new IOException("registration identity differs");
        return value;
    }
    public List<String> applications() throws IOException {
        try (var entries=Files.list(root)) {
            return entries.filter(Files::isDirectory).filter(p -> p.getFileName().toString().matches("[a-z0-9][a-z0-9_-]{0,62}"))
                .filter(p -> Files.exists(p.resolve("registration.json"))).map(p -> p.getFileName().toString()).sorted().limit(65).toList();
        }
    }
    public Registration register(ManagedApplication application,IsolatedComposeClient.Target target) throws Exception {
        return registry.locked(() -> {
            if (applications().size()>=64) throw new IOException("first release supports at most 64 registered applications");
            for (String id:applications()) {
                var other=read(id);
                if (other.target()!=null && !id.equals(application.id()) && other.target().daemonId().equals(target.daemonId())
                        && other.target().containerId().equals(target.containerId()))
                    throw new IOException("container already belongs to another application in this workspace");
            }
            var incidents=new ManagedIncidentStore(directory(application.id()).resolve("controller"),application.id());
            try (var lease=incidents.claim()) {
                if (Files.exists(directory(application.id()).resolve("registration.json")))
                    throw new IOException("application is already registered; existing incidents and target pins must be preserved");
                var value=new Registration(application,ActionPolicy.ask(application,clock.instant().plusSeconds(86_400)),target,null);
                var files=new ManagedControlStore(directory(application.id()));
                files.write("policy-1-"+UUID.randomUUID()+".json",value);
                files.write("registration.json",value);
                return value;
            }
        });
    }
    public Registration registerRemote(ManagedApplication application,RemoteManagedSource source) throws Exception {
        source.binding().validate(application);
        return registry.locked(() -> {
            if (applications().size()>=64) throw new IOException("first release supports at most 64 registered applications");
            for (String id:applications()) {
                var other=read(id);
                if (other.remote()!=null && other.remote().binding().targetId().equals(source.binding().targetId())
                        && other.remote().binding().containerId().equals(source.binding().containerId()))
                    throw new IOException("remote container already belongs to an application in this workspace");
            }
            var incidents=new ManagedIncidentStore(directory(application.id()).resolve("controller"),application.id());
            try (var lease=incidents.claim()) {
                if (Files.exists(directory(application.id()).resolve("registration.json")))
                    throw new IOException("application is already registered; preserve existing identity and incidents");
                var policy=new ActionPolicy(application.id(),application.version(),1,ActionPolicy.Mode.OBSERVE,
                    ActionPolicy.Qualification.DRAFT,Set.of(),clock.instant().plusSeconds(86_400),1);
                var value=new Registration(application,policy,null,null,source);
                var files=new ManagedControlStore(directory(application.id()));
                files.write("policy-1-"+UUID.randomUUID()+".json",value); files.write("registration.json",value); return value;
            }
        });
    }
    /** Explicit user permission update. Qualification is a recorded human scope review, never model self-promotion. */
    public Registration setPolicy(String applicationId,ActionPolicy.Mode mode,Set<OpsDecision.Playbook> playbooks,
            Duration validity,String operator,String reviewNote) throws Exception {
        ManagedApplication.requireDuration(validity,Duration.ofMinutes(1),Duration.ofHours(24));
        return registry.locked(() -> {
            var previous=read(applicationId); var app=previous.application();
            if (previous.remote()!=null && mode!=ActionPolicy.Mode.OBSERVE)
                throw new IllegalArgumentException("remote observation has no qualified repair contract; only observe is supported");
            if (playbooks.isEmpty()) throw new IllegalArgumentException("choose at least one reviewed action");
            ScopeReview review=null;
            if (mode==ActionPolicy.Mode.LIMITED_AUTO) {
                if (!app.repairIntendedAt(clock.instant())) throw new IllegalArgumentException("application intent does not permit automatic repair");
                review=new ScopeReview(operator,reviewNote,clock.instant(),ManagedContracts.hash(app),ManagedContracts.hash(previous.target()));
            }
            var policy=new ActionPolicy(app.id(),app.version(),previous.policy().version()+1,mode,
                mode==ActionPolicy.Mode.LIMITED_AUTO ? ActionPolicy.Qualification.QUALIFIED : ActionPolicy.Qualification.DRAFT,
                playbooks,clock.instant().plus(validity),1);
            var value=new Registration(app,policy,previous.target(),review,previous.remote());
            var files=new ManagedControlStore(directory(applicationId));
            files.write("policy-"+policy.version()+"-"+UUID.randomUUID()+".json",value);
            files.write("registration.json",value);
            return value;
        });
    }
}
