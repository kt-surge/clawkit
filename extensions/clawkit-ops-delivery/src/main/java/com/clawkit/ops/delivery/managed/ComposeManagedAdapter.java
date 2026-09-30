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

/** Production composition adapter. HTTP reads have no redirects and bounded body size; raw logs never enter model context. */
public final class ComposeManagedAdapter implements ManagedObserver,ManagedFixAdapter {
    private final IsolatedComposeClient compose;
    private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public ComposeManagedAdapter(IsolatedComposeClient compose,Clock clock) { this.compose=Objects.requireNonNull(compose); this.clock=Objects.requireNonNull(clock); }
    private void match(ManagedApplication app) {
        if (!app.composeProject().equals(compose.target().project()) || !app.service().equals(compose.target().service()))
            throw new IllegalArgumentException("application differs from pinned Compose identity");
    }
    @Override public Observation observe(ManagedApplication app,Probe probe) throws Exception {
        match(app);
        Status status; String detail;
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
                var health=compose.dependenciesHealth();
                status=switch(health) { case HEALTHY -> Status.HEALTHY; case UNHEALTHY -> Status.UNHEALTHY; case UNKNOWN -> Status.UNKNOWN; };
                detail="declared dependencies="+compose.target().dependencies().size()+" verifiedHealth="+health;
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
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    status=Status.UNHEALTHY; detail="HTTP unavailable: "+e.getClass().getSimpleName();
                }
            }
            case LOGS -> { compose.service(); status=Status.UNKNOWN; detail="Raw container logs are not exposed; use service/HTTP/dependency evidence or hand off."; }
            default -> throw new IllegalArgumentException("unknown probe");
        }
        return new Observation(app.targetId(),app.composeProject(),app.service(),probe,clock.instant(),status,detail);
    }
    @Override public ExecutionReport execute(ManagedApplication app,OpsDecision.Playbook playbook) throws Exception {
        match(app);
        var result=compose.execute(playbook==OpsDecision.Playbook.START_STOPPED_V1 ? IsolatedComposeClient.Action.START : IsolatedComposeClient.Action.RESTART);
        return new ExecutionReport(result.success() && !result.truncated() ? EffectCertainty.EFFECT_CONFIRMED : EffectCertainty.EFFECT_UNKNOWN,
            "registered container action exit="+result.exitCode()+" timedOut="+result.timedOut()+" truncated="+result.truncated());
    }
    @Override public void close() { http.close(); }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream(4097);
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            this.subscription=subscription; subscription.request(1);
        }
        @Override public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            for (var buffer:buffers) {
                int count=Math.min(buffer.remaining(),4097-bytes.size());
                byte[] chunk=new byte[count]; buffer.get(chunk); bytes.writeBytes(chunk);
                if (bytes.size()>=4097) { subscription.cancel(); result.complete(bytes.toByteArray()); return; }
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
