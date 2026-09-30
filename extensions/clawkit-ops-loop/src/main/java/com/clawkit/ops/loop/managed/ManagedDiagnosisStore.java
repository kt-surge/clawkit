package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Standard diagnostic records never contain raw model exchanges. Every attempt has an immutable artifact. */
public final class ManagedDiagnosisStore {
    public record Record(String applicationId,Instant at,OpsDecisionAgent.Origin origin,OpsDecision decision,
                         DiagnosticReport diagnosis,List<DecisionEvidence> evidence,
                         List<ManagedIncidentController.ProviderUsage> providerUsage,List<String> rejectedSubmissions,
                         String failureType) {
        public Record { evidence=List.copyOf(evidence); providerUsage=List.copyOf(providerUsage); rejectedSubmissions=List.copyOf(rejectedSubmissions); }
    }
    public record Pointer(String filename) {
        public Pointer { if (filename==null || !filename.matches("diagnosis-[a-f0-9-]{36}\\.json")) throw new IllegalArgumentException("invalid diagnosis pointer"); }
    }
    private final Path root;
    private final ManagedControlStore files;
    public ManagedDiagnosisStore(Path root) throws IOException { this.root=root.toAbsolutePath().normalize(); files=new ManagedControlStore(this.root); }
    public Path save(ManagedApplication app,OpsDecisionAgent.Outcome outcome,Instant at) throws Exception {
        var record=new Record(app.id(),at,outcome.origin(),outcome.decision(),outcome.diagnosis(),outcome.evidence(),
            outcome.providerExchanges().stream().map(e -> new ManagedIncidentController.ProviderUsage(e.startedAt(),e.completedAt(),e.usage(),e.failureType())).toList(),
            outcome.rejectedSubmissions(),outcome.failureType());
        if (ManagedContracts.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(record).length>262144)
            throw new IOException("diagnosis record exceeds byte cap; no new pointer published");
        return files.locked(() -> {
            String name="diagnosis-"+UUID.randomUUID()+".json"; files.write(name,record);
            files.write("latest.json",new Pointer(name)); return root.resolve(name);
        });
    }
    public Record latest(String applicationId) throws IOException {
        Path pointer=root.resolve("latest.json"); if (!Files.exists(pointer)) return null;
        var value=read(pointer,Pointer.class,1024);
        var record=read(root.resolve(value.filename()),Record.class,262144);
        if (!applicationId.equals(record.applicationId())) throw new IOException("diagnosis record identity mismatch");
        return record;
    }
    public Path latestPath() throws IOException {
        Path pointer=root.resolve("latest.json"); return Files.exists(pointer) ? root.resolve(read(pointer,Pointer.class,1024).filename()) : root;
    }
    private static <T> T read(Path path,Class<T> type,int cap) throws IOException {
        try (var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(cap+1); if (bytes.length>cap) throw new IOException("diagnosis record exceeds byte cap");
            return ManagedContracts.JSON.readValue(bytes,type);
        }
    }
}
