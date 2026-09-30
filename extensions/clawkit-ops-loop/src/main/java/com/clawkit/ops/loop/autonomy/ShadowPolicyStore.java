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
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Immutable on-disk policy store. Each policy is content-addressed by policyHash
 * and protected by a SHA-256 footer; policy changes require a new policy object.
 */
public final class ShadowPolicyStore {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String FOOTER_PREFIX = "# SHA-256: ";
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCAL_LOCKS = new ConcurrentHashMap<>();

    private final Path directory;

    public ShadowPolicyStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public AutoRemediationPolicy put(AutoRemediationPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        return withExclusive(() -> putWithinExclusive(policy));
    }

    private AutoRemediationPolicy putWithinExclusive(AutoRemediationPolicy policy) throws IOException {
        String hash = policy.policyHash();
        Path target = pathFor(hash);
        if (Files.exists(target)) return load(hash);
        String payload = MAPPER.writeValueAsString(Map.of("policy", policy, "policyHash", hash));
        writeNew(target, payload);
        return load(hash);
    }

    /** Serializes first policy creation across local JVMs before existence is checked. */
    private <T> T withExclusive(IoSupplier<T> operation) throws IOException {
        Files.createDirectories(directory);
        Path lockPath = directory.resolve(".shadow-policy.lock").toAbsolutePath().normalize();
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

    public AutoRemediationPolicy load(String policyHash) throws IOException {
        String payload = readVerified(pathFor(policyHash));
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            throw new IOException("Shadow policy JSON is invalid", e);
        }
        if (!root.isObject() || root.size() != 2 || !root.has("policy") || !root.has("policyHash")) {
            throw new IOException("Shadow policy schema is invalid");
        }
        if (!policyHash.equals(root.path("policyHash").asText())) {
            throw new IOException("Shadow policy hash does not match its file name");
        }
        AutoRemediationPolicy policy;
        try {
            policy = MAPPER.treeToValue(root.path("policy"), AutoRemediationPolicy.class);
        } catch (Exception e) {
            throw new IOException("Shadow policy body is invalid", e);
        }
        if (!policyHash.equals(policy.policyHash())) {
            throw new IOException("Shadow policy content hash mismatch");
        }
        return policy;
    }

    Path pathFor(String policyHash) {
        if (policyHash == null || !policyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("policyHash must be a SHA-256 hex value");
        }
        return directory.resolve(policyHash + ".json");
    }

    private static void writeNew(Path target, String payload) throws IOException {
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
        } catch (FileAlreadyExistsException e) {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            // On Windows an atomic non-replacing move can surface as AccessDeniedException
            // when a concurrent workflow has just created the content-addressed target.
            // The winner's immutable file is the only valid value to reuse; never replace it.
            if (Files.isRegularFile(target)) {
                Files.deleteIfExists(temp);
                return;
            }
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private static String readVerified(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Shadow policy does not exist: " + path.getFileName());
        String[] lines = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1);
        if (lines.length != 3 || lines[0].isBlank() || !lines[1].startsWith(FOOTER_PREFIX) || !lines[2].isEmpty()) {
            throw new IOException("Shadow policy footer is invalid");
        }
        String expected = lines[1].substring(FOOTER_PREFIX.length());
        if (!expected.matches("[0-9a-f]{64}") || !expected.equals(checksum(lines[0]))) {
            throw new IOException("Shadow policy checksum mismatch");
        }
        return lines[0];
    }

    private static String checksum(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required for Shadow policy", e);
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private static FileLock acquireLock(FileChannel channel, Path lockPath) throws IOException {
        try {
            return channel.lock();
        } catch (OverlappingFileLockException e) {
            throw new IOException("Shadow policy lock overlaps in this JVM: " + lockPath.getFileName(), e);
        }
    }
}
