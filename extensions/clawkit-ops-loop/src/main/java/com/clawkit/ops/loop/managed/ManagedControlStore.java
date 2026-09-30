package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.*;

/** One controller workspace. Control writes are forced before atomic replacement; malformed state fails closed. */
public final class ManagedControlStore {
    private final Path root;
    public record Degradation(String applicationId, String attemptId, String reason, Instant at) {}
    public record AuthorizationRecord(String source, RepairAuthorization authorization) {}
    @FunctionalInterface interface Work<T> { T run() throws Exception; }
    public ManagedControlStore(Path root) throws IOException {
        this.root=root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
    }
    <T> T locked(Work<T> work) throws Exception {
        try (FileChannel channel=FileChannel.open(root.resolve("execution.lock"),CREATE,WRITE)) {
            try (FileLock lock=channel.tryLock()) {
                if (lock==null) throw new IOException("another controller operation holds the workspace lock");
                return work.run();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                throw new IOException("another controller operation holds the workspace lock",e);
            }
        }
    }
    public Optional<Degradation> degradation(String applicationId) throws IOException {
        Path path=root.resolve("degraded-"+ManagedApplication.identifier(applicationId)+".json");
        if (!Files.exists(path)) return Optional.empty();
        Degradation value=ManagedContracts.JSON.readValue(path.toFile(),Degradation.class);
        if (!applicationId.equals(value.applicationId()) || value.reason()==null || value.at()==null)
            throw new IOException("invalid persistent degradation record");
        return Optional.of(value);
    }
    void degrade(ManagedApplication app,String attemptId,String reason,Instant at) throws IOException {
        write("degraded-"+app.id()+".json",new Degradation(app.id(),attemptId,reason,at));
    }
    void authorize(String attemptId,RepairAuthorization authorization) throws IOException {
        ManagedApplication.identifier(attemptId);
        write("authorization-"+attemptId+".json",new AuthorizationRecord(
            authorization instanceof RepairAuthorization.Human ? "HUMAN" : "POLICY",authorization));
    }
    void write(String filename,Object value) throws IOException {
        if (filename==null || !filename.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,150}"))
            throw new IllegalArgumentException("control filename must be controller-generated");
        Path target=root.resolve(filename);
        Path temporary=root.resolve(filename+"."+UUID.randomUUID()+".tmp");
        byte[] bytes=ManagedContracts.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        try {
            try (FileChannel channel=FileChannel.open(temporary,CREATE_NEW,WRITE)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // No non-atomic fallback for safety control state. Unsupported filesystems cannot enable writes.
            Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
