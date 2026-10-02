package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/** Server-side reads use bounded values; the only mutation is restart of one exact full container id. */
public final class PinnedDockerRestart implements PinnedRestartService.Backend {
    private static final String INSPECT="{{json .Id}} {{json .Config.Labels}} {{json .State.Running}} {{json .State.Restarting}} {{json .HostConfig.RestartPolicy.Name}} {{json .Mounts}} {{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}}";
    private final CommandExecutor commands;
    public PinnedDockerRestart(CommandExecutor commands) { this.commands=Objects.requireNonNull(commands); }
    @Override public void precheck(PinnedRestartContract.Target target) throws Exception {
        if(!target.composeHash().equals(IsolatedComposeClient.fileHash(Path.of(target.composeFile()))))
            throw new IllegalStateException("Compose digest changed");
        if(!target.endpoint().equals(read(target,List.of("context","inspect",target.context(),"--format","{{.Endpoints.docker.Host}}")))
                || !"linux".equals(read(target,List.of("info","--format","{{.OSType}}")))
                || !target.daemonId().equals(read(target,List.of("info","--format","{{.ID}}"))))
            throw new IllegalStateException("daemon identity changed");
        inspect(target,target.service(),target.containerId(),true);
        for(var dependency:target.dependencies().entrySet()) inspect(target,dependency.getKey(),dependency.getValue(),false);
    }
    private void inspect(PinnedRestartContract.Target target,String service,String containerId,boolean repair) throws Exception {
        List<JsonNode> parts;
        try(var parser=PinnedRestartContract.JSON.createParser(read(target,List.of("inspect","--format",INSPECT,containerId)));
            var values=PinnedRestartContract.JSON.readValues(parser,JsonNode.class)) { parts=values.readAll(); }
        if(parts.size()!=7 || !parts.get(0).isTextual() || !parts.get(1).isObject() || !parts.get(2).isBoolean()
                || !parts.get(3).isBoolean() || !parts.get(4).isTextual() || !parts.get(5).isArray() || !parts.get(6).isTextual())
            throw new IllegalStateException("bounded container facts are incomplete");
        var labels=parts.get(1);
        if(!containerId.equals(parts.get(0).asText()) || !target.project().equals(labels.path("com.docker.compose.project").asText())
                || !service.equals(labels.path("com.docker.compose.service").asText())
                || !Path.of(target.composeFile()).normalize().equals(Path.of(labels.path("com.docker.compose.project.config_files").asText()).toAbsolutePath().normalize())
                || !parts.get(2).asBoolean() || parts.get(3).asBoolean()) throw new IllegalStateException("fixed live container identity changed");
        if(!repair) {
            if(!"healthy".equals(parts.get(6).asText())) throw new IllegalStateException("dependency health not confirmed");
            return;
        }
        if(!"unhealthy".equals(parts.get(6).asText()) || !Set.of("no","").contains(parts.get(4).asText()))
            throw new IllegalStateException("restart condition/native recovery differs");
        Set<String> destinations=new HashSet<>();
        for(var mount:parts.get(5)) {
            String destination=mount.path("Destination").asText(); var pin=target.mounts().get(destination);
            if(!destinations.add(destination) || pin==null || !mount.path("RW").isBoolean() || mount.path("RW").asBoolean()
                    || !"bind".equals(mount.path("Type").asText()) || !pin.source().equals(mount.path("Source").asText())
                    || !pin.contentHash().equals(IsolatedComposeClient.contentHash(Path.of(pin.hostPath()))))
                throw new IllegalStateException("read-only configuration mount changed");
        }
        if(!destinations.equals(target.mounts().keySet())) throw new IllegalStateException("configuration mount set changed");
    }
    @Override public CommandResult restart(PinnedRestartContract.Target target) {
        return commands.execute(command(target,List.of("restart","--time","5",target.containerId())),Map.of(),Duration.ofSeconds(20),1024);
    }
    private String read(PinnedRestartContract.Target target,List<String> arguments) throws Exception {
        var result=commands.execute(command(target,arguments),Map.of(),Duration.ofSeconds(5),16384);
        if(!result.success() || result.truncated()) throw new IllegalStateException("bounded identity read failed");
        return result.stdout().strip();
    }
    private static List<String> command(PinnedRestartContract.Target target,List<String> arguments) {
        var command=new ArrayList<>(List.of("docker","--host",target.endpoint())); command.addAll(arguments); return List.copyOf(command);
    }
}
