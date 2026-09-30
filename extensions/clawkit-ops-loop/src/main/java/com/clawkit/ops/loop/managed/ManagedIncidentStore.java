package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static java.nio.file.StandardOpenOption.*;

/** Snapshot is the control fact source; pending event bridges the snapshot -> event log/outbox projection window. */
public final class ManagedIncidentStore {
    public enum Mode { RUNNING, PAUSED, STOPPED }
    public record RequestedMode(Mode mode,Instant at) {}
    public record ObservationView(List<ManagedObserver.Observation> observations,String source) {
        public ObservationView { observations=List.copyOf(observations); }
    }
    public record Summary(String id,ManagedIncident.State state,Instant createdAt,Instant lastObservedAt,String detail) {}
    public record Event(String id,long sequence,String applicationId,String incidentId,String kind,
                        ManagedIncident.State state,Instant at,String detail) {}
    public record Snapshot(long sequence,ManagedIncident current,List<Summary> history,Event pendingEvent,Instant lastTick) {
        public Snapshot {
            history=List.copyOf(history);
            if (sequence<0 || history.size()>100) throw new IllegalArgumentException("invalid controller snapshot");
        }
        public static Snapshot empty() { return new Snapshot(0,null,List.of(),null,null); }
    }
    private final Path root;
    private final String applicationId;
    private final ManagedControlStore files;
    public ManagedIncidentStore(Path root,String applicationId) throws IOException {
        this.root=root.toAbsolutePath().normalize(); this.applicationId=ManagedApplication.identifier(applicationId);
        this.files=new ManagedControlStore(this.root);
    }
    public Lease claim() throws IOException {
        FileChannel channel=FileChannel.open(root.resolve(applicationId+".controller.lock"),CREATE,WRITE);
        try {
            FileLock lock=channel.tryLock();
            if (lock==null) throw new IOException("application is already controlled by another process");
            return new Lease(channel,lock);
        } catch (Exception e) { channel.close(); throw new IOException("application is already controlled or lease unavailable",e); }
    }
    public record Lease(FileChannel channel,FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { lock.close(); } finally { channel.close(); } }
    }
    public boolean processActive() throws IOException {
        try (var channel=FileChannel.open(root.resolve(applicationId+".controller.lock"),CREATE,WRITE)) {
            try {
                FileLock lock=channel.tryLock(); if (lock==null) return true;
                try (lock) { return false; }
            } catch (java.nio.channels.OverlappingFileLockException e) { return true; }
        }
    }
    public Mode requestedMode() throws IOException {
        Path file=root.resolve(applicationId+".mode.json");
        if (!Files.exists(file)) return Mode.STOPPED;
        RequestedMode mode=ManagedContracts.JSON.readValue(file.toFile(),RequestedMode.class);
        if (mode.mode()==null || mode.at()==null) throw new IOException("controller mode record is malformed");
        return mode.mode();
    }
    public void requestMode(Mode mode,Instant at) throws IOException { files.write(applicationId+".mode.json",new RequestedMode(mode,at)); }
    public Snapshot read() throws IOException {
        Path file=root.resolve(applicationId+".snapshot.json");
        if (!Files.exists(file)) return Snapshot.empty();
        Snapshot snapshot=ManagedContracts.JSON.readValue(file.toFile(),Snapshot.class);
        if (snapshot.current()!=null && !applicationId.equals(snapshot.current().applicationId())) throw new IOException("controller snapshot identity differs");
        return snapshot;
    }
    void artifact(String filename,Object value) throws IOException { files.write(filename,value); }
    void observations(List<ManagedObserver.Observation> observations,String source) throws IOException {
        files.write(applicationId+".observations.json",new ObservationView(observations,source));
    }
    void observations(IndependentManagedVerifier.Result result) throws IOException {
        if (result!=null && !result.samples().isEmpty())
            observations(result.samples().getLast().observations(),"INDEPENDENT_VERIFICATION");
    }
    public ObservationView observations() throws IOException {
        Path file=root.resolve(applicationId+".observations.json");
        return Files.exists(file) ? ManagedContracts.JSON.readValue(file.toFile(),ObservationView.class) : null;
    }
    void heartbeat(Instant at) throws IOException {
        Snapshot snapshot=read();
        if (snapshot.pendingEvent()!=null) throw new IOException("event projection pending");
        files.write(applicationId+".snapshot.json",new Snapshot(snapshot.sequence(),snapshot.current(),snapshot.history(),null,at));
    }
    public Path artifactPath(String filename) {
        if (filename==null || !filename.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,150}")) throw new IllegalArgumentException("invalid artifact name");
        return root.resolve(filename);
    }
    /** Bounded tail view; the Snapshot remains authoritative if an event-log line is incomplete or corrupt. */
    public List<Event> recentEvents(int limit) throws IOException {
        if (limit<1 || limit>100) throw new IllegalArgumentException("recent event limit must be 1..100");
        Path path=root.resolve(applicationId+".events.jsonl"); if (!Files.exists(path)) return List.of();
        byte[] bytes;
        boolean partial;
        try (var channel=FileChannel.open(path,READ)) {
            int size=(int)Math.min(channel.size(),262_144); partial=channel.size()>size;
            channel.position(Math.max(0,channel.size()-size)); ByteBuffer buffer=ByteBuffer.allocate(size);
            while (buffer.hasRemaining() && channel.read(buffer)>0) {} bytes=buffer.array();
        }
        String[] lines=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).split("\n"); List<Event> events=new ArrayList<>();
        for (int i=partial ? 1 : 0;i<lines.length;i++) {
            try { var event=ManagedContracts.JSON.readValue(lines[i],Event.class); if (applicationId.equals(event.applicationId())) events.add(event); }
            catch (Exception ignored) {}
        }
        return events.stream().collect(java.util.stream.Collectors.toMap(Event::id,e -> e,(first,last) -> last))
            .values().stream().sorted(java.util.Comparator.comparingLong(Event::sequence).reversed()).limit(limit).toList();
    }
    void save(ManagedIncident incident,String kind,Instant at) throws IOException {
        Snapshot before=read();
        if (before.pendingEvent()!=null) throw new IOException("prior event projection must finish before another transition");
        if (!applicationId.equals(incident.applicationId())) throw new IOException("incident application differs");
        List<Summary> history=new ArrayList<>(before.history());
        if (before.current()!=null && !before.current().id().equals(incident.id())) {
            var prior=before.current();
            history.add(new Summary(prior.id(),prior.state(),prior.createdAt(),prior.lastObservedAt(),prior.detail()));
            if (history.size()>100) history.removeFirst();
        }
        long sequence=before.sequence()+1;
        Event event=new Event(applicationId+"-"+sequence,sequence,applicationId,incident.id(),kind,incident.state(),at,incident.detail());
        files.write(applicationId+".snapshot.json",new Snapshot(sequence,incident,history,event,at));
    }
    /** At-least-once projection: notification consumers use Event.id to deduplicate replays after a crash. */
    void project(java.util.function.Consumer<Event> listener) throws IOException {
        Snapshot snapshot=read(); Event event=snapshot.pendingEvent();
        if (event==null) return;
        byte[] line=(ManagedContracts.JSON.writeValueAsString(event)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var channel=FileChannel.open(root.resolve(applicationId+".events.jsonl"),CREATE,WRITE,APPEND)) {
            ByteBuffer buffer=ByteBuffer.wrap(line); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        // A failed listener leaves pendingEvent intact. It must be an idempotent local outbox enqueue, not an external send.
        listener.accept(event);
        files.write(applicationId+".snapshot.json",new Snapshot(snapshot.sequence(),snapshot.current(),snapshot.history(),null,snapshot.lastTick()));
    }
}
