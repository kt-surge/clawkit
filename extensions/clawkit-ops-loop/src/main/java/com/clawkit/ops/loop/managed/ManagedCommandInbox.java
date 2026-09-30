package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Durable UI command inbox. Only the process holding the application's controller lease consumes commands. */
public final class ManagedCommandInbox {
    public enum Type { APPROVE, REJECT }
    public record Command(String id,Type type,String incidentId,String applicationHash,OpsDecision.Playbook playbook,
            String operator,Instant issuedAt,Instant expiresAt) {
        public Command {
            if (id==null || !id.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("generated command id required");
            UUID.fromString(id); Objects.requireNonNull(type); ManagedApplication.identifier(incidentId);
            Objects.requireNonNull(applicationHash); Objects.requireNonNull(playbook);
            if (operator==null || operator.isBlank() || operator.length()>100) throw new IllegalArgumentException("human operator required");
            if (issuedAt==null || expiresAt==null || !issuedAt.isBefore(expiresAt) || Duration.between(issuedAt,expiresAt).compareTo(Duration.ofMinutes(5))>0)
                throw new IllegalArgumentException("bounded command consent lifetime required");
        }
    }
    public record Receipt(String commandId,String incidentId,boolean applied,String detail,Instant at) {}
    private final Path root;
    private final ManagedControlStore files;
    private final Clock clock;
    public ManagedCommandInbox(Path root,Clock clock) throws IOException { this.root=root.toAbsolutePath().normalize(); files=new ManagedControlStore(this.root); this.clock=clock; }
    public Command enqueue(Type type,ManagedIncident incident,String operator) throws IOException {
        if (incident==null || incident.state()!=ManagedIncident.State.AWAITING_APPROVAL || incident.decision()==null)
            throw new IllegalArgumentException("incident is not awaiting human approval");
        if (pending().size()>=100) throw new IOException("command queue is full");
        var command=new Command(UUID.randomUUID().toString(),type,incident.id(),incident.applicationHash(),incident.decision().playbook(),
            operator,clock.instant(),clock.instant().plusSeconds(300));
        files.write("request-"+command.id()+".json",command); return command;
    }
    public List<Command> pending() throws IOException {
        List<Command> commands=new ArrayList<>();
        try (var entries=Files.list(root)) {
            for (Path file:entries.filter(p -> p.getFileName().toString().matches("request-[a-f0-9-]{36}\\.json"))
                    .filter(p -> !Files.exists(root.resolve(p.getFileName().toString().replace("request-","receipt-")))).limit(101).toList()) {
                if (Files.size(file)>4096) throw new IOException("command record exceeds size limit");
                Command command=ManagedContracts.JSON.readValue(file.toFile(),Command.class);
                if (!file.getFileName().toString().equals("request-"+command.id()+".json")) throw new IOException("command identity differs");
                if (!Files.exists(root.resolve("receipt-"+command.id()+".json"))) commands.add(command);
            }
        }
        if (commands.size()>100) throw new IOException("command queue exceeds supported size");
        commands.sort(Comparator.comparing(Command::issuedAt).thenComparing(Command::id)); return List.copyOf(commands);
    }
    public Receipt receipt(String commandId) throws IOException {
        UUID.fromString(commandId); Path path=root.resolve("receipt-"+commandId+".json");
        return Files.exists(path) ? ManagedContracts.JSON.readValue(path.toFile(),Receipt.class) : null;
    }
    public void complete(Command command,boolean applied,String detail) throws IOException {
        if (detail==null || detail.length()>500) throw new IllegalArgumentException("bounded command result required");
        files.write("receipt-"+command.id()+".json",new Receipt(command.id(),command.incidentId(),applied,detail,clock.instant()));
    }
}
