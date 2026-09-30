package com.clawkit.ops.delivery.managed;

import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.IsolatedComposeClient;
import com.clawkit.tools.action.EffectCertainty;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Pinned, bounded and redacted read probes. Source text is untrusted evidence, never executable input. */
public final class ComposeManagedAdapter implements ManagedObserver,ManagedFixAdapter {
    private final IsolatedComposeClient compose;
    private final Clock clock;
    private final ManagedChangeStore changes;
    private final MetricSource metrics;
    @FunctionalInterface public interface MetricSource extends AutoCloseable {
        Observation observe(ManagedApplication app) throws Exception;
        @Override default void close() throws Exception {}
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public ComposeManagedAdapter(IsolatedComposeClient compose,Clock clock) { this(compose,clock,null,null); }
    public ComposeManagedAdapter(IsolatedComposeClient compose,Clock clock,ManagedChangeStore changes,MetricSource metrics) {
        this.compose=Objects.requireNonNull(compose); this.clock=Objects.requireNonNull(clock); this.changes=changes; this.metrics=metrics;
    }
    private void match(ManagedApplication app) {
        if (!app.composeProject().equals(compose.target().project()) || !app.service().equals(compose.target().service()))
            throw new IllegalArgumentException("application differs from pinned Compose identity");
    }
    @Override public Observation observe(ManagedApplication app,Probe probe) throws Exception {
        match(app);
        Status status; String detail;
        var at=clock.instant();
        EvidenceEnvelope.Payload payload=null;
        switch (probe) {
            case SERVICE -> {
                var service=compose.service();
                boolean nativeManaged=!(service.restartPolicy().isEmpty() || "no".equals(service.restartPolicy()));
                status=service.writableMount() || nativeManaged ? Status.UNKNOWN
                    : service.restarting() || "restarting".equals(service.state()) ? Status.RESTARTING
                    : "running".equals(service.state()) ? Status.RUNNING
                    : java.util.Set.of("exited","created").contains(service.state()) ? Status.STOPPED : Status.UNKNOWN;
                detail="container="+service.id()+" state="+service.state()+" writableMount="+service.writableMount()+" nativeRestart="+nativeManaged;
            }
            case DEPENDENCIES -> {
                var facts=compose.dependencyFacts();
                status=facts.stream().anyMatch(f -> !f.state().equals("running") || f.restarting() || f.health().equals("unhealthy")) ? Status.UNHEALTHY
                    : facts.stream().anyMatch(f -> !f.health().equals("healthy")) ? Status.UNKNOWN : Status.HEALTHY;
                detail="declared dependencies="+facts.size()+" verifiedHealth="+status+" facts="
                    +facts.stream().map(f -> f.service()+":state="+f.state()+",health="+f.health()).toList();
                if (detail.length()>1200) {
                    detail=detail.substring(0,1200); status=Status.UNKNOWN;
                    payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.TRUNCATED,
                        at,at,clock.instant(),"dependencies","snapshot","dependency summary cap reached",null),null,java.util.List.of(),java.util.List.of());
                }
            }
            case HEALTH,BUSINESS -> {
                // Identity/config check also guards against collecting HTTP evidence for a replaced application.
                compose.service();
                var uri=probe==Probe.HEALTH ? app.healthUri() : app.businessUri();
                try {
                    var future=http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                        info -> new BoundedBodySubscriber());
                    HttpResponse<byte[]> response;
                    try { response=future.get(3,java.util.concurrent.TimeUnit.SECONDS); }
                    catch (Exception e) { future.cancel(true); throw e; }
                    byte[] body=response.body();
                    boolean bounded=body.length<=4096;
                    boolean marker=probe==Probe.HEALTH || (bounded && new String(body,java.nio.charset.StandardCharsets.UTF_8).contains(app.businessMarker()));
                    status=response.statusCode()==200 && bounded && marker ? Status.HEALTHY : Status.UNHEALTHY;
                    detail="httpStatus="+response.statusCode()+" boundedBody="+bounded+" expectedBusinessMarker="+marker;
                    if (bounded && response.statusCode()>=400) {
                        try {
                            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                            var error=json.path("error");
                            if (error.isTextual()) {
                                String code=com.clawkit.ops.mcp.LogSanitizer.sanitizeAll(error.asText()).text();
                                detail+=" error="+code.substring(0,Math.min(300,code.length()));
                            }
                        } catch (java.io.IOException ignored) { /* Non-JSON response bodies are not copied into context. */ }
                    }
                    if (!bounded) payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.HTTP,EvidenceEnvelope.Quality.TRUNCATED,
                        at,at,clock.instant(),"bytes","response","HTTP body exceeds 4096 byte cap",null),null,java.util.List.of(),java.util.List.of());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    status=Status.UNHEALTHY; detail="HTTP unavailable: "+e.getClass().getSimpleName();
                }
            }
            case LOGS -> {
                var start=at.minusSeconds(120); var logs=compose.logs(start,at);
                status=Status.UNKNOWN;
                detail=logs.redactedText().isBlank() ? "No log lines returned in the bounded window; this is not proof of no error." : logs.redactedText();
                payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.DOCKER_LOGS,
                    logs.truncated() ? EvidenceEnvelope.Quality.TRUNCATED : logs.complete() ? EvidenceEnvelope.Quality.COMPLETE : EvidenceEnvelope.Quality.ERROR,
                    start,at,clock.instant(),"lines","tail-200",logs.limitation(),null),null,java.util.List.of(),java.util.List.of());
            }
            case RESOURCES -> {
                var resource=compose.resources(); status=Status.UNKNOWN; detail=resource.limitation();
                payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.DOCKER_INSPECT,EvidenceEnvelope.Quality.COMPLETE,
                    at,at,clock.instant(),"bytes","snapshot",resource.limitation(),null),
                    new EvidenceEnvelope.ResourceFacts(resource.oomKilled(),resource.exitCode(),resource.finishedAt(),resource.memoryUsageBytes(),resource.memoryLimitBytes()),
                    java.util.List.of(),java.util.List.of());
            }
            case CHANGES -> {
                compose.service(); var start=at.minusSeconds(86400);
                var records=changes==null ? java.util.List.<EvidenceEnvelope.ChangeRecord>of() : changes.within(app,start,at);
                status=Status.UNKNOWN; detail=changes==null ? "Change source not configured; missing history is not evidence of no deployment."
                    : "Imported change records="+records.size()+"; operator assertions require corroboration; time proximity is correlation only.";
                payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.CHANGE_IMPORT,
                    changes==null ? EvidenceEnvelope.Quality.MISSING : EvidenceEnvelope.Quality.COMPLETE,start,at,clock.instant(),"records","last-24h",detail,null),
                    null,records,java.util.List.of());
            }
            case METRICS -> {
                compose.service();
                if (metrics!=null) return metrics.observe(app);
                status=Status.UNKNOWN; detail="No registered metric endpoint; no historical memory or error trend is available.";
                payload=EvidenceEnvelope.Payload.snapshot(EvidenceEnvelope.Source.PROMETHEUS,EvidenceEnvelope.Quality.MISSING,at,detail);
            }
            default -> throw new IllegalArgumentException("unknown probe");
        }
        if (payload==null) payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(
            probe==Probe.HEALTH || probe==Probe.BUSINESS ? EvidenceEnvelope.Source.HTTP : EvidenceEnvelope.Source.DOCKER_INSPECT,
            status==Status.UNKNOWN ? EvidenceEnvelope.Quality.MISSING : EvidenceEnvelope.Quality.COMPLETE,at,at,clock.instant(),"status","snapshot","snapshot only",null),
            null,java.util.List.of(),java.util.List.of());
        return new Observation(app.targetId(),app.composeProject(),app.service(),probe,at,status,detail,payload);
    }
    @Override public ExecutionReport execute(ManagedApplication app,OpsDecision.Playbook playbook) throws Exception {
        match(app);
        var result=compose.execute(playbook==OpsDecision.Playbook.START_STOPPED_V1 ? IsolatedComposeClient.Action.START : IsolatedComposeClient.Action.RESTART);
        return new ExecutionReport(result.success() && !result.truncated() ? EffectCertainty.EFFECT_CONFIRMED : EffectCertainty.EFFECT_UNKNOWN,
            "registered container action exit="+result.exitCode()+" timedOut="+result.timedOut()+" truncated="+result.truncated());
    }
    @Override public void close() throws Exception { try { http.close(); } finally { if (metrics!=null) metrics.close(); } }

    static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final java.io.ByteArrayOutputStream bytes;
        BoundedBodySubscriber() { this(4096); }
        BoundedBodySubscriber(int limit) { this.limit=limit; this.bytes=new java.io.ByteArrayOutputStream(limit+1); }
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription=subscription; subscription.request(1);
        }
        @Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer:buffers) {
                int count=Math.min(buffer.remaining(),limit+1-bytes.size());
                byte[] chunk=new byte[count]; buffer.get(chunk); bytes.writeBytes(chunk);
                if (bytes.size()>=limit+1) { subscription.cancel(); result.complete(bytes.toByteArray()); return; }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
