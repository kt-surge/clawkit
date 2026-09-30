package com.clawkit.ops.loop.managed;

import com.clawkit.ops.loop.notify.NotificationOutbox;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Controlled send transport only; none of these tests proves actual Feishu delivery. */
class ManagedLifecycleNotifierTest {
    @TempDir Path root;
    final MutableClock clock=new MutableClock();
    final String chat="oc_controlledRecipient";
    ManagedLifecycleNotifier notifier(ManagedLifecycleNotifier.Sender sender) throws Exception {
        return new ManagedLifecycleNotifier(root,ManagedDecisionTest.app(),chat,sender,clock);
    }
    ManagedIncidentStore.Event event(String kind) {
        return new ManagedIncidentStore.Event("demo-1",1,"demo","inc-controlled",kind,ManagedIncident.State.OPEN,clock.instant(),"bounded detail");
    }
    @Test void replayAndRepeatedObservationsDoNotProduceExtraNotifications() throws Exception {
        var sends=new AtomicInteger(); var notifier=notifier((recipient,text,key) -> {
            assertThat(recipient).isEqualTo(chat); assertThat(text).contains("目标：clawkit-autonomy-test/demo-api").doesNotContain(chat); sends.incrementAndGet(); return "om_controlled";
        });
        notifier.enqueue(event("OBSERVATION_MERGED")); assertThat(notifier.summary().pending()).isZero();
        notifier.enqueue(event("INCIDENT_CREATED")); notifier.enqueue(event("INCIDENT_CREATED"));
        assertThat(notifier.summary().pending()).isEqualTo(1); assertThat(sends).hasValue(0);
        notifier.flush(); notifier.enqueue(event("INCIDENT_CREATED")); notifier.flush();
        assertThat(sends).hasValue(1); assertThat(notifier.summary().sent()).isEqualTo(1);
        try (var files=Files.walk(root)) {
            for (Path file:files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".json")).toList())
                assertThat(Files.readString(file)).doesNotContain(chat);
        }
    }
    @Test void retryUsesSameIdAndPreservesAttemptCountWithoutPersistingSecretErrorMessages() throws Exception {
        List<String> keys=new ArrayList<>();
        var first=notifier((recipient,text,key) -> { keys.add(key); throw new java.io.IOException("private-secret-must-not-leak"); });
        first.enqueue(event("INCIDENT_CREATED")); first.flush(); first.enqueue(event("INCIDENT_CREATED"));
        first.flush(); assertThat(keys).hasSize(1);
        clock.advance(Duration.ofSeconds(11));
        var reopened=notifier((recipient,text,key) -> { keys.add(key); return "om_retried"; }); reopened.flush();
        assertThat(keys).hasSize(2); assertThat(keys.get(0)).isEqualTo(keys.get(1));
        var entries=new NotificationOutbox(root.resolve("outbox")).listAll(); assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().attemptCount()).isEqualTo(2); assertThat(reopened.summary().sent()).isEqualTo(1);
        assertThat(Files.readString(root.resolve("outbox").resolve(keys.getFirst()+".json"))).doesNotContain("private-secret");
    }
    @Test void dispatchCrashWithinWindowReusesRemoteDedupId() throws Exception {
        Set<String> delivered=new HashSet<>();
        var first=notifier((recipient,text,key) -> { delivered.add(key); throw new AssertionError("simulated process death after remote acceptance"); });
        first.enqueue(event("INCIDENT_CREATED")); assertThatThrownBy(first::flush).isInstanceOf(AssertionError.class);
        assertThat(new NotificationOutbox(root.resolve("outbox")).listAll().getFirst().state()).isEqualTo(NotificationOutbox.State.DISPATCHING);
        clock.advance(Duration.ofSeconds(30));
        var reopened=notifier((recipient,text,key) -> { delivered.add(key); return "om_deduplicated"; }); reopened.flush();
        assertThat(delivered).hasSize(1); assertThat(reopened.summary().sent()).isEqualTo(1);
    }
    @Test void ambiguousDispatchOutsideRemoteWindowIsNotResent() throws Exception {
        var sends=new AtomicInteger(); var first=notifier((recipient,text,key) -> { sends.incrementAndGet(); throw new AssertionError("process died"); });
        first.enqueue(event("INCIDENT_CREATED")); assertThatThrownBy(first::flush).isInstanceOf(AssertionError.class);
        clock.advance(Duration.ofMinutes(51)); notifier((recipient,text,key) -> { sends.incrementAndGet(); return "om_shouldNotSend"; }).flush();
        assertThat(sends).hasValue(1);
        var entry=new NotificationOutbox(root.resolve("outbox")).listAll().getFirst();
        assertThat(entry.state()).isEqualTo(NotificationOutbox.State.PERMANENT_FAILED); assertThat(entry.failureReason()).contains("dedupe window expired");
    }
    @Test void deliveryLeasePreventsTwoConcurrentSenders() throws Exception {
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var sends=new AtomicInteger();
        var first=notifier((recipient,text,key) -> { sends.incrementAndGet(); entered.countDown(); if (!release.await(3,TimeUnit.SECONDS)) throw new java.io.IOException("test timeout"); return "om_first"; });
        first.enqueue(event("INCIDENT_CREATED"));
        try (var executor=Executors.newSingleThreadExecutor()) {
            var future=executor.submit(() -> { try { first.flush(); } catch (Exception e) { throw new RuntimeException(e); } });
            try {
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                notifier((recipient,text,key) -> { sends.incrementAndGet(); return "om_duplicate"; }).flush(); assertThat(sends).hasValue(1);
            } finally { release.countDown(); }
            future.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void permanentApiFailureStopsRetriesAndDoesNotExposeCredentialText() throws Exception {
        var sends=new AtomicInteger(); var notifier=notifier((recipient,text,key) -> { sends.incrementAndGet(); throw new ManagedLifecycleNotifier.SendFailure(false); });
        notifier.enqueue(event("INCIDENT_CREATED")); notifier.flush(); clock.advance(Duration.ofSeconds(60)); notifier.flush();
        assertThat(sends).hasValue(1); assertThat(notifier.summary().failed()).isEqualTo(1);
    }
    private static final class MutableClock extends Clock {
        Instant now=ManagedDecisionTest.NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now=now.plus(duration); }
    }
}
