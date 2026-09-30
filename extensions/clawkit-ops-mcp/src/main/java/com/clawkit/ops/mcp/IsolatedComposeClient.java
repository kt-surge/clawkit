package com.clawkit.ops.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Local Linux Docker only. Registered container IDs are pinned; no shell, creation, dependency restart or config update. */
public final class IsolatedComposeClient {
    private static final ObjectMapper JSON=new ObjectMapper();
    // Avoid embedded quotes/newlines in Windows ProcessBuilder arguments. These are seven bounded JSON values, not full inspect output.
    private static final String INSPECT="{{json .Id}} {{json .Config.Labels}} {{json .State.Status}} {{json .State.Restarting}} {{json .HostConfig.RestartPolicy.Name}} {{json .Mounts}} {{if .State.Health}}{{json .State.Health.Status}}{{else}}null{{end}}";
    public record Target(String context,String daemonId,String endpoint,String composeFile,String composeHash,
                         String project,String service,String containerId,Map<String,String> dependencies,Map<String,MountPin> mounts) {
        public Target(String context,String daemonId,String endpoint,String composeFile,String composeHash,
                String project,String service,String containerId,Map<String,String> dependencies) {
            this(context,daemonId,endpoint,composeFile,composeHash,project,service,containerId,dependencies,Map.of());
        }
        public Target {
            identifier(context); identifier(project); identifier(service);
            if (!project.startsWith("clawkit-autonomy-")) throw new IllegalArgumentException("isolated project prefix required");
            if (daemonId==null || daemonId.isBlank() || endpoint==null || composeHash==null || composeFile==null)
                throw new IllegalArgumentException("registered daemon/config identity required");
            IsolatedComposeClient.containerId(containerId);
            dependencies=Map.copyOf(dependencies);
            if (dependencies.size()>16) throw new IllegalArgumentException("at most sixteen registered dependencies supported");
            mounts=Map.copyOf(mounts);
            if (dependencies.containsKey(service)) throw new IllegalArgumentException("a service cannot depend on itself");
            dependencies.forEach((serviceName,id) -> { identifier(serviceName); IsolatedComposeClient.containerId(id); });
        }
    }
    public record MountPin(String source,String hostPath,String contentHash) {}
    public record Container(String id,String state,boolean restarting,String restartPolicy,boolean writableMount,String health) {}
    public enum DependencyHealth { HEALTHY, UNHEALTHY, UNKNOWN }
    public record DependencyFact(String service,String containerId,String state,boolean restarting,String health) {}
    public enum Action { START, RESTART }
    public record LogRead(String redactedText,boolean complete,boolean truncated,String limitation) {}
    public record ResourceSnapshot(boolean oomKilled,int exitCode,java.time.Instant finishedAt,
                                   Long memoryUsageBytes,Long memoryLimitBytes,String limitation) {}
    private final Target target;
    private final CommandExecutor commands;
    public IsolatedComposeClient(Target target,CommandExecutor commands) { this.target=Objects.requireNonNull(target); this.commands=Objects.requireNonNull(commands); }
    public Target target() { return target; }

    public static Target register(CommandExecutor commands,Path composeFile,String context,String project,String service,List<String> dependencies) throws Exception {
        identifier(context); identifier(project); identifier(service);
        if (dependencies==null || dependencies.size()>16) throw new IllegalArgumentException("at most sixteen registered dependencies supported");
        if (!project.startsWith("clawkit-autonomy-")) throw new IllegalArgumentException("isolated project prefix required");
        Path file=composeFile.toRealPath();
        String endpoint=read(commands,context,List.of("context","inspect",context,"--format","{{.Endpoints.docker.Host}}"));
        requireLocal(endpoint);
        if (!"linux".equals(read(commands,context,List.of("info","--format","{{.OSType}}"))))
            throw new IllegalArgumentException("Linux Docker daemon required");
        String daemonId=read(commands,context,List.of("info","--format","{{.ID}}"));
        String id=findContainer(commands,context,project,service);
        Map<String,String> dependencyIds=new TreeMap<>();
        for (String dependency:dependencies) { identifier(dependency); dependencyIds.put(dependency,findContainer(commands,context,project,dependency)); }
        Target target=new Target(context,daemonId,endpoint,file.toString(),fileHash(file),project,service,id,dependencyIds);
        Map<String,MountPin> pins=new TreeMap<>();
        for (JsonNode mount:new IsolatedComposeClient(target,commands).inspectParts(id).get(5)) {
            if (mount.path("RW").asBoolean(true) || !"bind".equals(mount.path("Type").asText()))
                throw new IllegalStateException("only read-only host configuration mounts are supported");
            String source=mount.path("Source").asText();
            Path host=hostPath(source).toRealPath();
            String destination=mount.path("Destination").asText();
            if (destination.isBlank() || pins.put(destination,new MountPin(source,host.toString(),contentHash(host)))!=null)
                throw new IllegalStateException("unique mount destination required");
        }
        target=new Target(context,daemonId,endpoint,file.toString(),fileHash(file),project,service,id,dependencyIds,pins);
        IsolatedComposeClient client=new IsolatedComposeClient(target,commands);
        client.service(); client.dependenciesHealth();
        return target;
    }

    public Container service() throws Exception { verifyEnvironment(); return inspect(target.service(),target.containerId()); }
    /** A fixed time window and tail, no follow or arbitrary container arguments. Stderr may contain Docker log data. */
    public LogRead logs(java.time.Instant since,java.time.Instant until) throws Exception {
        service();
        if (since==null || until==null || since.isAfter(until)
                || Duration.between(since,until).compareTo(Duration.ofMinutes(15))>0)
            throw new IllegalArgumentException("bounded log window required");
        var result=commands.execute(command(target.context(),List.of("logs","--since",since.toString(),"--until",until.toString(),
            "--tail","200","--timestamps",target.containerId())),Map.of(),Duration.ofSeconds(5),8192);
        if (!result.success()) return new LogRead("",false,result.truncated(),"container logs unavailable; no inference from missing output");
        String text=LogSanitizer.sanitizeAll(result.stdout()+"\n"+result.stderr()).text().strip();
        boolean truncated=result.truncated() || text.length()>1200 || text.contains("...[TRUNCATED]");
        if (text.length()>1200) text=text.substring(0,1200);
        // Tail itself is a sampling bound, not evidence that no older errors exist.
        return new LogRead(text,true,truncated,truncated ? "log byte/summary cap reached" : "last 200 lines within requested window; empty means no returned lines");
    }
    public ResourceSnapshot resources() throws Exception {
        Container container=service();
        String format="{{json .State.OOMKilled}} {{json .State.ExitCode}} {{json .State.FinishedAt}} {{json .HostConfig.Memory}}";
        String raw=read(commands,target.context(),List.of("inspect","--format",format,target.containerId()));
        List<JsonNode> parts;
        try (var parser=JSON.createParser(raw); var values=JSON.readValues(parser,JsonNode.class)) { parts=values.readAll(); }
        if (parts.size()!=4 || !parts.get(0).isBoolean() || !parts.get(1).isIntegralNumber() || !parts.get(3).isIntegralNumber())
            throw new IOException("resource inspect contract mismatch");
        java.time.Instant finished=parts.get(2).asText().startsWith("0001-") ? null : java.time.Instant.parse(parts.get(2).asText());
        long configuredLimit=parts.get(3).asLong();
        Long usage=null;
        String limitation="snapshot only; no historical trend";
        if ("running".equals(container.state())) {
            var result=commands.execute(command(target.context(),List.of("stats","--no-stream","--format","{{json .MemUsage}}",target.containerId())),
                Map.of(),Duration.ofSeconds(5),1024);
            if (result.success() && !result.truncated()) {
                try { usage=memoryBytes(JSON.readTree(result.stdout().strip()).asText().split("/",2)[0].strip()); }
                catch (Exception ignored) { limitation+="; memory usage unavailable"; }
            } else limitation+="; memory usage unavailable";
        } else limitation+="; stopped container has no current memory usage";
        return new ResourceSnapshot(parts.get(0).asBoolean(),parts.get(1).asInt(),finished,usage,
            configuredLimit>0 ? configuredLimit : null,limitation);
    }
    private static long memoryBytes(String value) {
        var matcher=java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*(B|KiB|MiB|GiB|TiB|kB|KB|MB|GB|TB)").matcher(value);
        if (!matcher.matches()) throw new IllegalArgumentException("unrecognized memory unit");
        String unit=matcher.group(2);
        int power=switch(unit) { case "B" -> 0; case "KiB","kB","KB" -> 1; case "MiB","MB" -> 2; case "GiB","GB" -> 3; default -> 4; };
        var bytes=new java.math.BigDecimal(matcher.group(1)).multiply(java.math.BigDecimal.valueOf(unit.contains("i") ? 1024 : 1000).pow(power));
        return bytes.setScale(0,java.math.RoundingMode.HALF_UP).longValueExact();
    }
    public DependencyHealth dependenciesHealth() throws Exception {
        boolean unknown=false;
        for (var container:dependencyFacts()) {
            if (!"running".equals(container.state()) || container.restarting() || "unhealthy".equals(container.health())) return DependencyHealth.UNHEALTHY;
            if (!"healthy".equals(container.health())) unknown=true;
        }
        return unknown ? DependencyHealth.UNKNOWN : DependencyHealth.HEALTHY;
    }
    public List<DependencyFact> dependencyFacts() throws Exception {
        verifyEnvironment(); var facts=new ArrayList<DependencyFact>();
        for (var entry:target.dependencies().entrySet()) {
            var container=inspect(entry.getKey(),entry.getValue());
            facts.add(new DependencyFact(entry.getKey(),container.id(),container.state(),container.restarting(),container.health()));
        }
        return List.copyOf(facts);
    }
    public CommandResult execute(Action action) throws Exception {
        Container current=service();
        if (current.writableMount() || !("no".equals(current.restartPolicy()) || current.restartPolicy().isEmpty()) || current.restarting())
            throw new IllegalStateException("persistent mount/native recovery prevents autonomous action");
        if (action==Action.START && !Set.of("exited","created").contains(current.state()))
            throw new IllegalStateException("registered container is not stopped");
        if (action==Action.RESTART && !"running".equals(current.state()))
            throw new IllegalStateException("registered container is not running");
        // Exact container identity remains fixed even if another Compose process replaces the service concurrently.
        List<String> command=action==Action.START ? List.of("start",target.containerId())
            : List.of("restart","--time","5",target.containerId());
        return commands.execute(command(target.context(),command),Map.of(),Duration.ofSeconds(20),8192);
    }
    private void verifyEnvironment() throws Exception {
        if (!target.composeHash().equals(fileHash(Path.of(target.composeFile())))) throw new IllegalStateException("registered Compose configuration changed");
        String endpoint=read(commands,target.context(),List.of("context","inspect",target.context(),"--format","{{.Endpoints.docker.Host}}"));
        requireLocal(endpoint);
        if (!target.endpoint().equals(endpoint) || !target.daemonId().equals(read(commands,target.context(),List.of("info","--format","{{.ID}}"))))
            throw new IllegalStateException("registered Docker daemon identity changed");
    }
    private Container inspect(String service,String id) throws Exception {
        var node=JSON.createObjectNode();
        List<JsonNode> parts=inspectParts(id);
        node.set("id",parts.get(0));
        JsonNode labels=parts.get(1);
        node.set("project",labels.path("com.docker.compose.project"));
        node.set("service",labels.path("com.docker.compose.service"));
        node.set("configFiles",labels.path("com.docker.compose.project.config_files"));
        node.set("state",parts.get(2)); node.set("restarting",parts.get(3)); node.set("restartPolicy",parts.get(4)); node.set("mounts",parts.get(5));
        if (!id.equals(node.path("id").asText()) || !target.project().equals(node.path("project").asText())
                || !service.equals(node.path("service").asText())
                || !Path.of(target.composeFile()).equals(Path.of(node.path("configFiles").asText()).toAbsolutePath().normalize()))
            throw new IllegalStateException("registered project/service/config identity mismatch");
        boolean writable=false;
        if (!node.path("mounts").isArray()) throw new IllegalStateException("mount evidence missing");
        for (JsonNode mount:node.path("mounts")) if (mount.path("RW").asBoolean(true)) writable=true;
        if (service.equals(target.service())) {
            Set<String> destinations=new HashSet<>();
            for (JsonNode mount:node.path("mounts")) {
                String destination=mount.path("Destination").asText();
                MountPin pin=target.mounts().get(destination);
                if (!destinations.add(destination) || pin==null || mount.path("RW").asBoolean(true)
                        || !"bind".equals(mount.path("Type").asText()) || !pin.source().equals(mount.path("Source").asText())
                        || !pin.contentHash().equals(contentHash(Path.of(pin.hostPath()))))
                    throw new IllegalStateException("registered read-only mount identity/content changed");
            }
            if (!destinations.equals(target.mounts().keySet())) throw new IllegalStateException("registered mounts changed");
        }
        String state=node.path("state").asText();
        if (!Set.of("running","exited","created","restarting","paused","dead","removing").contains(state))
            throw new IllegalStateException("unrecognized container state");
        return new Container(id,state,node.path("restarting").asBoolean(),node.path("restartPolicy").asText(),writable,
            parts.get(6).isNull() ? "" : parts.get(6).asText());
    }
    private List<JsonNode> inspectParts(String id) throws IOException {
        String raw=read(commands,target.context(),List.of("inspect","--format",INSPECT,id));
        List<JsonNode> parts;
        try (var parser=JSON.createParser(raw); var values=JSON.readValues(parser,JsonNode.class)) { parts=values.readAll(); }
        if (parts.size()!=7 || !parts.get(5).isArray()) throw new IOException("bounded inspect values do not match the contract");
        return parts;
    }
    private static Path hostPath(String source) {
        // Docker Desktop sometimes reports its Linux translation of an explicitly mounted Windows host path.
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            for (String prefix:List.of("/run/desktop/mnt/host/","/host_mnt/")) {
                if (source.startsWith(prefix)) {
                    String suffix=source.substring(prefix.length());
                    if (suffix.matches("[a-zA-Z]/.+")) return Path.of(suffix.charAt(0)+":"+suffix.substring(1));
                }
            }
        }
        return Path.of(source);
    }
    private static String contentHash(Path root) throws Exception {
        if (Files.isSymbolicLink(root)) throw new IllegalStateException("symbolic configuration mounts are unsupported");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        List<Path> entries;
        if (Files.isRegularFile(root,LinkOption.NOFOLLOW_LINKS)) entries=List.of(root);
        else if (Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) {
            try (var walk=Files.walk(root,16)) {
                entries=walk.limit(258).sorted(Comparator.comparing(p -> root.relativize(p).toString())).toList();
            }
            if (entries.size()>256) throw new IllegalStateException("configuration mount exceeds entry limit");
        } else throw new IllegalStateException("host configuration mount is unavailable");
        long total=0;
        for (Path entry:entries) {
            if (Files.isSymbolicLink(entry)) throw new IllegalStateException("symbolic configuration content is unsupported");
            boolean directory=Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS);
            if (directory && !root.equals(entry) && root.relativize(entry).getNameCount()>=16)
                throw new IllegalStateException("configuration directory depth exceeds limit");
            if (!directory && !Files.isRegularFile(entry,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("unsupported configuration content");
            String relative=root.equals(entry) ? "." : root.relativize(entry).toString().replace('\\','/');
            digest.update((relative+"\0"+(directory ? "directory" : "file")+"\0").getBytes(StandardCharsets.UTF_8));
            if (directory) continue;
            // Bounded reads also protect against a file growing after its size was checked.
            try (var input=Files.newInputStream(entry)) {
                byte[] bytes=input.readNBytes(1_048_577);
                if (bytes.length>1_048_576 || (total+=bytes.length)>10_485_760) throw new IllegalStateException("configuration content exceeds byte limit");
                digest.update(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static String findContainer(CommandExecutor commands,String context,String project,String service) throws Exception {
        String id=read(commands,context,List.of("ps","-a","--no-trunc","--filter","label=com.docker.compose.project="+project,
            "--filter","label=com.docker.compose.service="+service,"--format","{{.ID}}"));
        containerId(id); return id;
    }
    private static String read(CommandExecutor commands,String context,List<String> args) throws IOException {
        CommandResult result=commands.execute(command(context,args),Map.of(),Duration.ofSeconds(5),16384);
        if (!result.success() || result.truncated()) throw new IOException("bounded Docker identity/read command failed");
        return result.stdout().trim();
    }
    private static List<String> command(String context,List<String> args) {
        List<String> result=new ArrayList<>(List.of("docker","--context",context)); result.addAll(args); return List.copyOf(result);
    }
    private static void requireLocal(String endpoint) {
        if (endpoint==null || !(endpoint.startsWith("unix:///") || endpoint.startsWith("npipe:////./pipe/")))
            throw new IllegalArgumentException("remote Docker endpoints are outside first-release authorization");
    }
    private static void identifier(String value) {
        if (value==null || !value.matches("[a-z0-9][a-z0-9_-]{0,62}")) throw new IllegalArgumentException("invalid registered Compose identity");
    }
    private static void containerId(String value) {
        if (value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("exactly one existing container required");
    }
    private static String fileHash(Path file) throws Exception {
        byte[] bytes;
        try (var input=Files.newInputStream(file)) { bytes=input.readNBytes(1_048_577); }
        if (bytes.length>1_048_576) throw new IllegalStateException("Compose configuration exceeds byte limit");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
