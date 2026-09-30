package com.clawkit.ops.loop.autonomy;

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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Immutable, checksummed review store. It stores review evidence only, never an ApprovalGrant. */
public final class ShadowReviewStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String FOOTER = "# SHA-256: ";
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();
    private final Path directory;

    public ShadowReviewStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public RecordedReview record(ShadowReview review) throws IOException {
        Objects.requireNonNull(review, "review");
        return withExclusive(() -> recordWithinLock(review));
    }

    private RecordedReview recordWithinLock(ShadowReview review) throws IOException {
        Path target = pathFor(review.decisionId());
        if (Files.exists(target)) return replay(target, review);
        String payload = MAPPER.writeValueAsString(review);
        if (writeNew(target, payload)) return new RecordedReview(load(review.decisionId()), true);
        return replay(target, review);
    }

    /**
     * Serializes review creation across local JVMs before the existence check.
     * This is evidence storage only; acquiring the lock never opens a session,
     * requests approval, or invokes a repair tool.
     */
    private <T> T withExclusive(IoSupplier<T> operation) throws IOException {
        Files.createDirectories(directory);
        Path lockPath = directory.resolve(".shadow-review.lock").toAbsolutePath().normalize();
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

    public ShadowReview load(String decisionId) throws IOException {
        String payload = readVerified(pathFor(decisionId));
        try {
            ShadowReview review = MAPPER.readValue(payload, ShadowReview.class);
            if (!decisionId.equals(review.decisionId())) throw new IOException("Shadow review decision id mismatch");
            return review;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Shadow review JSON is invalid", e);
        }
    }

    private Path pathFor(String decisionId) {
        if (decisionId == null || !decisionId.matches("shadow-[0-9a-f]{32}")) {
            throw new IllegalArgumentException("decisionId must be a deterministic Shadow id");
        }
        return directory.resolve(decisionId + ".json");
    }

    private RecordedReview replay(Path target, ShadowReview candidate) throws IOException {
        ShadowReview existing = load(candidate.decisionId());
        if (!existing.reviewId().equals(candidate.reviewId())
            || existing.reviewerDecision() != candidate.reviewerDecision()
            || !existing.policyHash().equals(candidate.policyHash())
            || !existing.evidenceSnapshotHash().equals(candidate.evidenceSnapshotHash())) {
            throw new IOException("Shadow review already exists with a different immutable decision");
        }
        return new RecordedReview(existing, false);
    }

    private static boolean writeNew(Path target, String payload) throws IOException {
        Files.createDirectories(target.getParent());
        String content = payload + "\n" + FOOTER + Hashing.sha256(payload) + "\n";
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
            // Windows can report the concurrent, immutable target as access denied
            // instead of FileAlreadyExistsException. Reuse it; never overwrite a review.
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(temp);
                return false;
            }
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static String readVerified(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Shadow review does not exist: " + path.getFileName());
        String[] lines = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[1].startsWith(FOOTER) || !lines[2].isEmpty()
            || !lines[1].substring(FOOTER.length()).equals(Hashing.sha256(lines[0]))) {
            throw new IOException("Shadow review checksum is invalid");
        }
        return lines[0];
    }

    public record RecordedReview(ShadowReview review, boolean created) { }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static FileLock acquireLock(FileChannel channel, Path lockPath) throws IOException {
        try {
            return channel.lock();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Shadow review lock overlaps in this JVM: " + lockPath.getFileName(), e);
        }
    }
}
