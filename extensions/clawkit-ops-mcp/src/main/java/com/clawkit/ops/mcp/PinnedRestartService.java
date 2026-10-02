package com.clawkit.ops.mcp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static com.clawkit.ops.mcp.PinnedRestartContract.*;

/** Cross-process target lock + durable intents. Restart/replay never dispatches an unresolved intent. */
public final class PinnedRestartService {
    @FunctionalInterface public interface ConfigurationSource { Configuration read() throws Exception; }
    public interface Backend {
        void precheck(Target target) throws Exception;
        CommandResult restart(Target target) throws Exception;
    }
    private final Path directory;
    private final ConfigurationSource configurations;
    private final Backend backend;
    private final Clock clock;
    public PinnedRestartService(Path directory,ConfigurationSource configurations,Backend backend,Clock clock) throws IOException {
        this.directory=directory.toAbsolutePath().normalize(); this.configurations=Objects.requireNonNull(configurations);
        this.backend=Objects.requireNonNull(backend); this.clock=Objects.requireNonNull(clock);
        Files.createDirectories(this.directory);
        if(Files.isSymbolicLink(this.directory)) throw new IOException("symbolic execution store is prohibited");
    }
    public Configuration scope() throws Exception { return configurations.read(); }
    public Receipt receipt(String requestId) throws Exception {
        requestId(requestId);
        try(var channel=FileChannel.open(directory.resolve("target.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=lock(channel)) {
            if(lock==null) throw new IOException("target busy; receipt unavailable");
            var intent=readIntent(requestId);
            return intent==null ? null : recorded(intent);
        }
    }
    public Receipt restart(Request request) throws Exception {
        Objects.requireNonNull(request);
        try(var channel=FileChannel.open(directory.resolve("target.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=lock(channel)) {
            if(lock==null) return reject(request,"TARGET_BUSY");
            var prior=readIntent(request.requestId());
            if(prior!=null) return prior.requestHash().equals(request.fingerprint()) ? recorded(prior) : reject(request,"REQUEST_ID_CONFLICT");
            var scope=configurations.read();
            if(!directory.equals(Path.of(scope.stateDirectory()).toAbsolutePath().normalize()) || !request.matches(scope)) return reject(request,"SCOPE_CHANGED");
            if(!scope.activeAt(clock.instant())) return reject(request,"GRANT_INACTIVE");
            var history=intents();
            for(var intent:history) {
                if(intent.request().incidentId().equals(request.incidentId())) return reject(request,"INCIDENT_ALREADY_DISPATCHED");
                if(recorded(intent).status()==Status.UNKNOWN) return reject(request,"UNRESOLVED_DISPATCH");
                if(intent.request().grantVersion()==scope.grantVersion()) return reject(request,"GRANT_BUDGET_EXHAUSTED");
            }
            try { backend.precheck(scope.target()); }
            catch(Exception e) { return reject(request,"PRECONDITION_FAILED"); }
            if(!sameActiveScope(scope)) return reject(request,"GRANT_CHANGED_DURING_PRECHECK");
            var intent=new Intent(VERSION,request,request.fingerprint(),clock.instant());
            write(request.requestId()+".intent.json",intent);
            try { backend.precheck(scope.target()); }
            catch(Exception e) { return finish(request,Status.REJECTED,"PRECONDITION_CHANGED_BEFORE_DISPATCH"); }
            // Revocation/expiry after durable intent still produces a durable no-effect receipt.
            if(!sameActiveScope(scope)) return finish(request,Status.REJECTED,"GRANT_CHANGED_BEFORE_DISPATCH");
            try {
                var outcome=backend.restart(scope.target());
                return finish(request,outcome.success() ? Status.DISPATCH_REPORTED : Status.UNKNOWN,
                    outcome.success() ? "COMMAND_REPORTED_SUCCESS" : "COMMAND_OUTCOME_UNKNOWN");
            } catch(Exception e) {
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                return finish(request,Status.UNKNOWN,"TRANSPORT_OUTCOME_UNKNOWN");
            }
        }
    }
    private boolean sameActiveScope(Configuration scope) throws Exception {
        var current=configurations.read(); return scope.equals(current) && current.activeAt(clock.instant());
    }
    private Receipt finish(Request request,Status status,String code) throws Exception {
        var value=PinnedRestartContract.receipt(request,status,code,clock.instant());
        write(request.requestId()+".receipt.json",value); return value;
    }
    private Receipt reject(Request request,String code) { return PinnedRestartContract.receipt(request,Status.REJECTED,code,clock.instant()); }
    private Intent readIntent(String requestId) throws Exception {
        Path file=directory.resolve(requestId+".intent.json");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return null;
        var intent=read(file,Intent.class);
        if(!requestId.equals(intent.request().requestId())) throw new IOException("intent file identity differs");
        return intent;
    }
    private Receipt recorded(Intent intent) throws Exception {
        Path file=directory.resolve(intent.request().requestId()+".receipt.json");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return PinnedRestartContract.receipt(intent.request(),Status.UNKNOWN,"DURABLE_INTENT_WITHOUT_RECEIPT",intent.at());
        var value=read(file,Receipt.class);
        if(!value.request().equals(intent.request()) || !value.requestHash().equals(intent.requestHash())) throw new IOException("receipt/intent mismatch");
        return value;
    }
    private List<Intent> intents() throws Exception {
        List<Path> entries;
        try(var stream=Files.list(directory)) { entries=stream.limit(4098).toList(); }
        if(entries.size()>4097) throw new IOException("execution history cap reached; operator review required");
        var files=entries.stream().filter(p -> p.getFileName().toString().endsWith(".intent.json")).toList();
        if(files.size()>2048) throw new IOException("execution intent cap reached");
        for(var file:entries) {
            String name=file.getFileName().toString();
            if(!name.endsWith(".receipt.json")) continue;
            String id=name.substring(0,name.length()-".receipt.json".length()); requestId(id);
            if(readIntent(id)==null) throw new IOException("orphan receipt; history loss requires operator review");
        }
        var values=new ArrayList<Intent>();
        for(var file:files) {
            String name=file.getFileName().toString(); String id=name.substring(0,name.length()-".intent.json".length());
            requestId(id); values.add(Objects.requireNonNull(readIntent(id)));
        }
        return List.copyOf(values);
    }
    private static FileLock lock(FileChannel channel) throws IOException {
        try { return channel.tryLock(); } catch(OverlappingFileLockException busy) { return null; }
    }
    private static <T> T read(Path file,Class<T> type) throws Exception {
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new IOException("invalid execution file");
        try(var stream=Files.newInputStream(file)) {
            byte[] bytes=stream.readNBytes(32769); if(bytes.length>32768) throw new IOException("execution record too large");
            return JSON.readValue(bytes,type);
        }
    }
    private void write(String name,Object value) throws Exception {
        byte[] bytes=JSON.writeValueAsBytes(value);
        try(var channel=FileChannel.open(directory.resolve(name),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            var buffer=ByteBuffer.wrap(bytes); while(buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        // Linux production requires directory durability. Windows isolated validation is not a power-loss certification.
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
            try(var channel=FileChannel.open(directory,StandardOpenOption.READ)) { channel.force(true); }
    }
}
