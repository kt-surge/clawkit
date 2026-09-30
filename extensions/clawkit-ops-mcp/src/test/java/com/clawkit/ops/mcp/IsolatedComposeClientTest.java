package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class IsolatedComposeClientTest {
    @TempDir Path root;
    private static final String ID="a".repeat(64);
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void remoteContextCannotEvenRegisterAndNoWriteCommandRuns() throws Exception {
        Path compose=root.resolve("compose.yaml"); Files.writeString(compose,"services: {}");
        var calls=new AtomicInteger();
        assertThatThrownBy(() -> IsolatedComposeClient.register((args,env,timeout,limit) -> {
            calls.incrementAndGet(); return ok("tcp://remote:2375");
        },compose,"default","clawkit-autonomy-test","demo-api",List.of()))
            .hasMessageContaining("remote Docker endpoints");
        assertThat(calls).hasValue(1);
    }

    @Test void projectMismatchWritableMountAndConfigDriftBlockBeforeMutation() throws Exception {
        for (String fault:List.of("wrong-project","writable-mount","config-drift")) {
            Path compose=root.resolve(fault+".yaml"); Files.writeString(compose,"services: {}");
            var writes=new AtomicInteger();
            var target=new IsolatedComposeClient.Target("default","daemon-1","unix:///var/run/docker.sock",compose.toString(),hash(compose),
                "clawkit-autonomy-test","demo-api",ID,Map.of());
            CommandExecutor executor=(args,env,timeout,limit) -> {
                assertThat(args.subList(0,3)).containsExactly("docker","--context","default");
                if (args.get(3).equals("context")) return ok(target.endpoint());
                if (args.get(3).equals("info")) return ok(target.daemonId());
                if (args.get(3).equals("inspect")) {
                    try {
                        var labels=JSON.createObjectNode().put("com.docker.compose.project",fault.equals("wrong-project") ? "other" : target.project())
                            .put("com.docker.compose.service",target.service()).put("com.docker.compose.project.config_files",compose.toString());
                        var mounts=JSON.createArrayNode(); if (fault.equals("writable-mount")) mounts.addObject().put("RW",true);
                        return ok(JSON.writeValueAsString(ID)+" "+labels+" \"exited\" false \"no\" "+mounts+" null");
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
                writes.incrementAndGet(); return ok(ID);
            };
            if (fault.equals("config-drift")) Files.writeString(compose,"services: {changed: {}}");
            assertThatThrownBy(() -> new IsolatedComposeClient(target,executor).execute(IsolatedComposeClient.Action.START))
                .isInstanceOf(IllegalStateException.class);
            assertThat(writes).hasValue(0);
        }
    }

    @Test void anActionTargetsOnlyThePinnedContainerWithAnExplicitContext() throws Exception {
        Path compose=root.resolve("compose.yaml"); Files.writeString(compose,"services: {}");
        var target=new IsolatedComposeClient.Target("default","daemon-1","unix:///var/run/docker.sock",compose.toString(),hash(compose),
            "clawkit-autonomy-test","demo-api",ID,Map.of());
        List<List<String>> mutations=new ArrayList<>();
        CommandExecutor executor=(args,env,timeout,limit) -> {
            if (args.get(3).equals("context")) return ok(target.endpoint());
            if (args.get(3).equals("info")) return ok(target.daemonId());
            if (args.get(3).equals("inspect")) {
                try {
                    var labels=JSON.createObjectNode().put("com.docker.compose.project",target.project()).put("com.docker.compose.service",target.service())
                        .put("com.docker.compose.project.config_files",compose.toString());
                    return ok(JSON.writeValueAsString(ID)+" "+labels+" \"exited\" false \"no\" [] null");
                } catch (Exception e) { throw new IllegalStateException(e); }
            }
            mutations.add(args); return ok(ID);
        };
        assertThat(new IsolatedComposeClient(target,executor).execute(IsolatedComposeClient.Action.START).success()).isTrue();
        assertThat(mutations).containsExactly(List.of("docker","--context","default","start",ID));
    }
    @Test void registrationPinsReadOnlyContentAndChangedOrMissingContentBlocksMutation() throws Exception {
        for (String fault:List.of("changed","deleted","added-tree-entry")) {
            Path directory=Files.createDirectory(root.resolve(fault));
            Path compose=directory.resolve("compose.yaml"); Files.writeString(compose,"services: {}");
            Path config=Files.createDirectory(directory.resolve("config")); Files.writeString(config.resolve("app.conf"),"healthy");
            var writes=new AtomicInteger();
            CommandExecutor executor=(args,env,timeout,limit) -> {
                if (args.get(3).equals("context")) return ok("unix:///var/run/docker.sock");
                if (args.get(3).equals("info")) return ok(args.get(5).equals("{{.OSType}}") ? "linux" : "daemon-1");
                if (args.get(3).equals("ps")) return ok(ID);
                if (args.get(3).equals("inspect")) {
                    try {
                        var labels=JSON.createObjectNode().put("com.docker.compose.project","clawkit-autonomy-test")
                            .put("com.docker.compose.service","demo-api").put("com.docker.compose.project.config_files",compose.toString());
                        var mounts=JSON.createArrayNode(); mounts.addObject().put("RW",false).put("Type","bind")
                            .put("Source",config.toString()).put("Destination","/app/config");
                        return ok(JSON.writeValueAsString(ID)+" "+labels+" \"exited\" false \"no\" "+mounts+" null");
                    } catch (Exception e) { throw new IllegalStateException(e); }
                }
                writes.incrementAndGet(); return ok(ID);
            };
            var target=IsolatedComposeClient.register(executor,compose,"default","clawkit-autonomy-test","demo-api",List.of());
            assertThat(target.mounts()).hasSize(1);
            if (fault.equals("changed")) Files.writeString(config.resolve("app.conf"),"changed");
            else if (fault.equals("deleted")) Files.delete(config.resolve("app.conf"));
            else Files.writeString(config.resolve("extra.conf"),"new");
            assertThatThrownBy(() -> new IsolatedComposeClient(target,executor).execute(IsolatedComposeClient.Action.START))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("content changed");
            assertThat(writes).hasValue(0);
        }
    }
    private static CommandResult ok(String text) { return new CommandResult(0,text,"",false,false,text.length()); }
    private static String hash(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
