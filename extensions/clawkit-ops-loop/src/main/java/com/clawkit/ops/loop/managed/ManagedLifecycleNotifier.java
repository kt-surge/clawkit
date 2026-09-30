package com.clawkit.ops.loop.managed;

import com.clawkit.ops.loop.notify.NotificationOutbox;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static java.nio.file.StandardOpenOption.*;

/** Local event enqueue and independent bounded delivery pump. Delivery never grants permission or changes an incident. */
public final class ManagedLifecycleNotifier {
    @FunctionalInterface public interface Sender { String send(String chatId,String text,String idempotencyKey) throws Exception; }
    public static final class SendFailure extends IOException {
        private final boolean retryable;
        public SendFailure(boolean retryable) { super(retryable ? "retryable delivery failure" : "permanent delivery failure"); this.retryable=retryable; }
        public boolean retryable() { return retryable; }
    }
    public record Payload(String eventId,String applicationId,String target,String incidentId,String state,Instant at) {}
    public record DeliveryControl(Instant firstDispatchAt,Instant nextAttemptAt) {}
    public record Summary(long pending,long sent,long failed) {}
    private final Path root;
    private final ManagedControlStore files;
    private final NotificationOutbox outbox;
    private final String chatId;
    private final String applicationId;
    private final String target;
    private final Clock clock;
    private final Sender sender;
    public ManagedLifecycleNotifier(Path root,ManagedApplication app,String chatId,Sender sender,Clock clock) throws IOException {
        if (chatId==null || !chatId.matches("oc_[a-zA-Z0-9]+")) throw new IllegalArgumentException("explicit fixed Feishu chat required");
        this.root=root.toAbsolutePath().normalize(); files=new ManagedControlStore(this.root); outbox=new NotificationOutbox(this.root.resolve("outbox"));
        this.applicationId=app.id(); this.target=app.composeProject()+"/"+app.service(); this.chatId=chatId; this.sender=Objects.requireNonNull(sender); this.clock=clock;
    }
    public void enqueue(ManagedIncidentStore.Event event) throws IOException {
        if (!applicationId.equals(event.applicationId())) throw new IllegalArgumentException("notification application differs");
        boolean important=event.kind().equals("INCIDENT_CREATED") || event.kind().equals("INCIDENT_RECOVERED")
            || event.kind().contains("HANDOFF") || event.kind().equals("HUMAN_REJECTED")
            || event.kind().equals("DECISION_SUBMITTED") && event.state()==ManagedIncident.State.AWAITING_APPROVAL;
        if (!important) return;
        String key=NotificationOutbox.idempotencyKey(event.incidentId(),event.id(),chatId,NotificationOutbox.EventType.STATUS_UPDATE);
        files.write("payload-"+key+".json",new Payload(event.id(),applicationId,target,event.incidentId(),event.state().name(),event.at()));
        outbox.upsertPending(event.incidentId(),event.id(),chatId,NotificationOutbox.EventType.STATUS_UPDATE);
    }
    /** Stable UUID has an upstream one-hour dedupe window. Ambiguous attempts older than 50 minutes are not resent. */
    public void flush() throws Exception {
        try (var channel=FileChannel.open(root.resolve("delivery.lock"),CREATE,WRITE)) {
            FileLock lock;
            try { lock=channel.tryLock(); } catch (OverlappingFileLockException e) { return; }
            if (lock==null) return;
            try (lock) {
                var entries=outbox.listAll().stream().filter(e -> e.state()!=NotificationOutbox.State.SENT && e.state()!=NotificationOutbox.State.PERMANENT_FAILED)
                    .sorted(Comparator.comparing(NotificationOutbox.Entry::createdAt)).limit(20).toList();
                for (var entry:entries) {
                    String key=entry.idempotencyKey();
                    if (!NotificationOutbox.hashChatId(chatId).equals(entry.chatIdHash())) continue;
                    Path controlFile=root.resolve("delivery-"+key+".json");
                    DeliveryControl control=Files.exists(controlFile) ? ManagedContracts.JSON.readValue(controlFile.toFile(),DeliveryControl.class) : null;
                    if (entry.attemptCount()>0 && control==null) {
                        outbox.markPermanentFailed(entry,"delivery outcome unknown; retry window unavailable"); continue;
                    }
                    if (control!=null && !clock.instant().isBefore(control.firstDispatchAt().plus(Duration.ofMinutes(50)))) {
                        outbox.markPermanentFailed(entry,"delivery outcome unknown; upstream dedupe window expired"); continue;
                    }
                    if (control!=null && clock.instant().isBefore(control.nextAttemptAt())) continue;
                    if (entry.attemptCount()>=4) { outbox.markPermanentFailed(entry,"delivery retry budget exhausted; inspect locally"); continue; }
                    Payload payload=ManagedContracts.JSON.readValue(root.resolve("payload-"+key+".json").toFile(),Payload.class);
                    if (!applicationId.equals(payload.applicationId()) || !entry.reportVersion().equals(payload.eventId())
                            || !entry.incidentId().equals(payload.incidentId()) || !target.equals(payload.target()))
                        throw new IOException("notification payload binding differs");
                    Instant first=control==null ? clock.instant() : control.firstDispatchAt();
                    long delay=Math.min(60,10L*(entry.attemptCount()+1));
                    files.write("delivery-"+key+".json",new DeliveryControl(first,clock.instant().plusSeconds(delay)));
                    entry=outbox.markDispatching(entry);
                    try {
                        String messageId=sender.send(chatId,render(payload),key);
                        if (messageId==null || messageId.isBlank() || messageId.length()>256) throw new IOException("delivery acknowledgement missing");
                        outbox.markSent(entry,messageId);
                    } catch (Exception e) {
                        String reason="delivery failed: "+e.getClass().getSimpleName(); // Never persist transport messages containing credentials.
                        if (e instanceof SendFailure failure && !failure.retryable()) outbox.markPermanentFailed(entry,reason);
                        else outbox.markRetryableFailed(entry,reason);
                        if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }
        }
    }
    public Summary summary() throws IOException {
        var entries=outbox.listAll();
        return new Summary(entries.stream().filter(e -> Set.of(NotificationOutbox.State.PENDING,NotificationOutbox.State.DISPATCHING,NotificationOutbox.State.RETRYABLE_FAILED).contains(e.state())).count(),
            entries.stream().filter(e -> e.state()==NotificationOutbox.State.SENT).count(),entries.stream().filter(e -> e.state()==NotificationOutbox.State.PERMANENT_FAILED).count());
    }
    public static String render(Payload payload) {
        String state=switch(payload.state()) { case "OPEN" -> "发现异常"; case "AWAITING_APPROVAL" -> "等待人工审批";
            case "RECOVERED" -> "已独立验证恢复"; case "CANCELLED" -> "已取消处理"; default -> "需要人工处理"; };
        return "CLAWKIT 运维通知\n应用："+payload.applicationId()+"\n目标："+payload.target()+"\n状态："+state
            +"\n事件："+payload.incidentId()+"\n时间："+payload.at()+"\n请在本地 CLI 查看 status/events；通知不批准或执行修复。";
    }
}
