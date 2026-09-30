package com.clawkit.ops.loop.autonomy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Immutable, idempotent A3 decision store. A stored decision never triggers a
 * write operation; it is only an auditable counterfactual record.
 */
public final class ShadowDecisionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String FOOTER_PREFIX = "# SHA-256: ";
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;

    public ShadowDecisionStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    /** Persist once; a semantic replay returns the pre-existing immutable decision. */
    public RecordedDecision record(ShadowDecision decision) throws IOException {
        Objects.requireNonNull(decision, "decision");
        return withExclusive(() -> recordWithinExclusive(decision));
    }

    /**
     * Persists while a caller already owns {@link #withExclusive(IoSupplier)}.
     * Package-visible only so ShadowWorkflow can keep counting and writing in
     * one transaction without attempting to re-enter the OS file lock.
     */
    RecordedDecision recordWithinExclusive(ShadowDecision decision) throws IOException {
        Objects.requireNonNull(decision, "decision");
        Path target = pathFor(decision.decisionId());
        if (Files.exists(target)) return replay(target, decision);
        String payload = MAPPER.writeValueAsString(decision);
        if (writeNew(target, payload)) {
            return new RecordedDecision(load(decision.decisionId()), true);
        }
        return replay(target, decision);
    }

    public ShadowDecision load(String decisionId) throws IOException {
        String payload = readVerified(pathFor(decisionId));
        try {
            ShadowDecision decision = MAPPER.readValue(payload, ShadowDecision.class);
            if (!decisionId.equals(decision.decisionId())) throw new IOException("Shadow decision id mismatch");
            return decision;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Shadow decision JSON is invalid", e);
        }
    }

    public Optional<ShadowDecision> find(String decisionId) throws IOException {
        Path path = pathFor(decisionId);
        return Files.exists(path) ? Optional.of(load(decisionId)) : Optional.empty();
    }

    public long countEligibleByPolicyHash(String policyHash) throws IOException {
        if (policyHash == null || !policyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("policyHash must be a SHA-256 hex value");
        }
        return list().stream()
            .filter(decision -> policyHash.equals(decision.policyHash()))
            .filter(decision -> decision.outcome() == ShadowOutcome.ELIGIBLE_SHADOW)
            .count();
    }

    /**
     * Serializes a read-count-record transaction across workflows and JVMs.
     * The lock protects only local Shadow evidence; it never guards or opens a
     * remote session. Failure to acquire it fails the evaluation closed.
     */
    public <T> T withExclusive(IoSupplier<T> operation) throws IOException {
        Objects.requireNonNull(operation, "operation");
        Files.createDirectories(directory);
        Path lockPath = directory.resolve(".shadow-decision.lock").toAbsolutePath().normalize();
        ReentrantLock localLock = LOCAL_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        localLock.lock();
        try (FileChannel channel = FileChannel.open(lockPath,
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = acquireLock(channel, lockPath)) {
            return operation.get();
        } finally {
            localLock.unlock();
        }
    }

    public List<ShadowDecision> list() throws IOException {
        if (!Files.exists(directory)) return List.of();
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                .map(path -> path.getFileName().toString().replaceFirst("\\.json$", ""))
                .map(id -> {
                    try {
                        return load(id);
                    } catch (IOException e) {
                        throw new ShadowStoreReadFailure(e);
                    }
                })
                .sorted(Comparator.comparing(ShadowDecision::decidedAt))
                .toList();
        } catch (ShadowStoreReadFailure e) {
            throw e.cause;
        }
    }

    Path pathFor(String decisionId) {
        if (decisionId == null || !decisionId.matches("shadow-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("decisionId must be a deterministic Shadow id");
        }
        return directory.resolve(decisionId + ".json");
    }

    private RecordedDecision replay(Path target, ShadowDecision candidate) throws IOException {
        ShadowDecision existing = load(candidate.decisionId());
        if (!sameIdentity(existing, candidate)) {
            throw new IOException("Shadow decision id collides with different evidence or policy");
        }
        return new RecordedDecision(existing, false);
    }

    private static boolean sameIdentity(ShadowDecision first, ShadowDecision second) {
        return first.incidentId().equals(second.incidentId())
            && first.targetId().equals(second.targetId())
            && first.evidenceSnapshotHash().equals(second.evidenceSnapshotHash())
            && first.policyHash().equals(second.policyHash())
            && first.candidateAction().equals(second.candidateAction())
            && first.candidateServiceId().equals(second.candidateServiceId());
    }

    private static boolean writeNew(Path target, String payload) throws IOException {
        Files.createDirectories(target.getParent());
        String content = payload + "\n" + FOOTER_PREFIX + checksum(payload) + "\n";
        Path temp = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(temp);
            return false;
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static String readVerified(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Shadow decision does not exist: " + path.getFileName());
        String[] lines = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[1].startsWith(FOOTER_PREFIX) || !lines[2].isEmpty()) {
            throw new IOException("Shadow decision footer is invalid");
        }
        String expected = lines[1].substring(FOOTER_PREFIX.length());
        if (!expected.matches("[0-9a-f]{64}") || !expected.equals(checksum(lines[0]))) {
            throw new IOException("Shadow decision checksum mismatch");
        }
        return lines[0];
    }

    private static String checksum(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for Shadow decision", e);
        }
    }

    public record RecordedDecision(ShadowDecision decision, boolean created) {}

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static FileLock acquireLock(FileChannel channel, Path lockPath) throws IOException {
        try {
            return channel.lock();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Shadow decision lock overlaps in this JVM: " + lockPath.getFileName(), e);
        }
    }

    private static final class ShadowStoreReadFailure extends RuntimeException {
        private final IOException cause;

        private ShadowStoreReadFailure(IOException cause) {
            this.cause = cause;
        }
    }
}
