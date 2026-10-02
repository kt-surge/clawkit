package com.clawkit.ops.mcp;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.clawkit.ops.mcp.PinnedRestartContract.*;
import static org.assertj.core.api.Assertions.*;

class PinnedDockerRestartTest {
    @TempDir Path root;
    final List<List<String>> mutations=new ArrayList<>();
    @Test void fullIdentityAndReadOnlyMountsAreRecheckedAndOnlyFixedHostContainerCanBeRestarted() throws Exception {
        var target=target("success"); var backend=new PinnedDockerRestart(commands(target,""));
        backend.precheck(target); backend.precheck(target);
        assertThat(backend.restart(target).success()).isTrue();
        assertThat(mutations).containsExactly(List.of("docker","--host",target.endpoint(),"restart","--time","5",target.containerId()));
    }
    @Test void configurationIdentityHealthAndMountDriftCauseZeroMutations() throws Exception {
        for(String fault:List.of("compose","daemon","container","project","healthy","native-recovery","missing-boolean","writable-mount","mount-content","dependency")) {
            var target=target(fault); var backend=new PinnedDockerRestart(commands(target,fault));
            if(fault.equals("compose")) Files.writeString(Path.of(target.composeFile()),"changed");
            if(fault.equals("mount-content")) Files.writeString(Path.of(target.mounts().get("/config").hostPath()),"changed");
            assertThatThrownBy(() -> backend.precheck(target)).as(fault).isInstanceOf(Exception.class);
        }
        assertThat(mutations).isEmpty();
    }
    Target target(String name) throws Exception {
        Path directory=Files.createDirectory(root.resolve(name)).toRealPath(); Path compose=directory.resolve("compose.yaml");
        Files.writeString(compose,"services: {}"); Path mount=directory.resolve("app.conf"); Files.writeString(mount,"fixed");
        return new Target("default","daemon-1","unix:///var/run/docker.sock",compose.toString(),IsolatedComposeClient.fileHash(compose),
            "reviewed-orders","order-api","b".repeat(64),Map.of("db","c".repeat(64)),
            Map.of("/config",new IsolatedComposeClient.MountPin(mount.toString(),mount.toString(),IsolatedComposeClient.contentHash(mount))));
    }
    CommandExecutor commands(Target target,String fault) {
        return (args,env,time,cap) -> {
            assertThat(args.subList(0,3)).containsExactly("docker","--host",target.endpoint());
            if(args.get(3).equals("context")) return ok(target.endpoint());
            if(args.get(3).equals("info")) return ok(args.getLast().equals("{{.OSType}}") ? "linux" : fault.equals("daemon") ? "other" : target.daemonId());
            if(args.get(3).equals("inspect")) {
                boolean repair=args.getLast().equals(target.containerId());
                var labels=JSON.createObjectNode().put("com.docker.compose.project",fault.equals("project") ? "other" : target.project())
                    .put("com.docker.compose.service",repair ? target.service() : "db").put("com.docker.compose.project.config_files",target.composeFile());
                var mounts=JSON.createArrayNode();
                if(repair) mounts.addObject().put("Destination","/config").put("Source",target.mounts().get("/config").source())
                    .put("Type","bind").put("RW",fault.equals("writable-mount"));
                String running=fault.equals("missing-boolean") ? "null" : "true";
                String health=repair ? fault.equals("healthy") ? "healthy" : "unhealthy" : fault.equals("dependency") ? "unhealthy" : "healthy";
                return ok("\""+(fault.equals("container") ? "d".repeat(64) : args.getLast())+"\" "+labels+" "+running+" false \""
                    +(fault.equals("native-recovery") ? "always" : "no")+"\" "+mounts+" \""+health+"\"");
            }
            mutations.add(args); return ok(target.containerId());
        };
    }
    static CommandResult ok(String text) { return new CommandResult(0,text,"",false,false,text.length()); }
}
