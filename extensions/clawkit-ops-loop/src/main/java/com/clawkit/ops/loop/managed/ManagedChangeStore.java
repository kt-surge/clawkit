package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Human/CI import is scoped to an already registered application. Imported text cannot change permissions. */
public final class ManagedChangeStore {
    private final Path root;
    private final ManagedControlStore files;
    private final Clock clock;
    public ManagedChangeStore(Path root,Clock clock) throws IOException {
        this.root=root.toAbsolutePath().normalize(); this.files=new ManagedControlStore(this.root); this.clock=clock;
    }
    public void importChange(ManagedApplication app,EvidenceEnvelope.ChangeRecord change) throws Exception {
        if (!change.matches(app) || change.occurredAt().isAfter(clock.instant()))
            throw new IllegalArgumentException("change does not match registered application/time");
        files.locked(() -> {
            Path path=root.resolve(change.id()+".json");
            if (Files.exists(path)) {
                if (!read(path).equals(change)) throw new IllegalArgumentException("change identity already has different content");
                return null;
            }
            try (var paths=Files.list(root)) {
                if (paths.filter(p -> p.getFileName().toString().endsWith(".json")).limit(257).count()>=256)
                    throw new IOException("change archive limit reached; archive old records explicitly");
            }
            files.write(change.id()+".json",change); return null;
        });
    }
    public List<EvidenceEnvelope.ChangeRecord> within(ManagedApplication app,Instant start,Instant end) throws IOException {
        List<Path> paths;
        try (var stream=Files.list(root)) { paths=stream.filter(p -> p.getFileName().toString().endsWith(".json")).limit(257).toList(); }
        if (paths.size()>256) throw new IOException("change archive exceeds bounded read");
        List<EvidenceEnvelope.ChangeRecord> result=new ArrayList<>();
        for (Path path:paths) {
            var record=read(path);
            if (!record.matches(app)) throw new IOException("change archive identity mismatch");
            if (!record.occurredAt().isBefore(start) && !record.occurredAt().isAfter(end)) result.add(record);
        }
        result.sort(Comparator.comparing(EvidenceEnvelope.ChangeRecord::occurredAt).reversed());
        if (result.size()>20) throw new IOException("change evidence window exceeds item cap");
        return List.copyOf(result);
    }
    private static EvidenceEnvelope.ChangeRecord read(Path path) throws IOException {
        try (var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(8193);
            if (bytes.length>8192) throw new IOException("change record exceeds byte cap");
            return ManagedContracts.JSON.readValue(bytes,EvidenceEnvelope.ChangeRecord.class);
        }
    }
}
