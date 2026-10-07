package com.clawkit.evaluation.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Fresh append-only experiment outputs; evaluator artifacts stay outside agent workspaces. */
public final class EvaluationArtifacts {
    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final Path root;

    public EvaluationArtifacts(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root.getParent());
        Files.createDirectory(this.root); // Refuse reuse, including an empty failed experiment.
    }

    public Path root() { return root; }
    public Path resolve(String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) throw new IllegalArgumentException("relative artifact path required");
        return resolved;
    }

    public void write(String relative, Object value) throws IOException {
        Path path = resolve(relative);
        Files.createDirectories(path.getParent());
        Files.write(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
    }

    public void append(String relative, Object value) throws IOException {
        Path path = resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON.writeValueAsString(value) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public void seal() throws Exception { write("artifact-hashes.json", hashes(root)); }

    public static Map<String, String> hashes(Path root) throws Exception {
        var hashes = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (!relative.equals("artifact-hashes.json")) hashes.put(relative, sha256(Files.readAllBytes(path)));
            }
        }
        return hashes;
    }

    public static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
