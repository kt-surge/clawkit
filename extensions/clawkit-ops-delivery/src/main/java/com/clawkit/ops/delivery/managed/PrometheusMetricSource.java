package com.clawkit.ops.delivery.managed;

import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.IsolatedComposeClient;
import com.clawkit.ops.mcp.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;

/** Optional cAdvisor memory history, fixed template and registered identity; no model-supplied URL or PromQL. */
public final class PrometheusMetricSource implements ComposeManagedAdapter.MetricSource {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final URI endpoint;
    private final IsolatedComposeClient.Target target;
    private final Clock clock;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    public PrometheusMetricSource(URI endpoint,IsolatedComposeClient.Target target,Clock clock) {
        new ManagedMetricStore.Configuration("validate",1,endpoint,clock.instant());
        this.endpoint=Objects.requireNonNull(endpoint); this.target=Objects.requireNonNull(target); this.clock=clock;
    }
    @Override public Observation observe(ManagedApplication app) throws Exception {
        if (!app.composeProject().equals(target.project()) || !app.service().equals(target.service()))
            throw new IllegalArgumentException("metric target differs from registered container");
        Instant end=clock.instant(),start=end.minusSeconds(300);
        EvidenceEnvelope.Quality quality=EvidenceEnvelope.Quality.COMPLETE;
        String limitation="fixed cAdvisor memory template; bounded five-minute history";
        List<EvidenceEnvelope.MetricSample> samples=new ArrayList<>();
        try {
            String query="container_memory_working_set_bytes{container_label_com_docker_compose_project=\""+target.project()
                +"\",container_label_com_docker_compose_service=\""+target.service()+"\",id=~\".*"+target.containerId()+".*\"}";
            String base=endpoint.toString().replaceAll("/+$","");
            URI uri=URI.create(base+"/api/v1/query_range?query="+URLEncoder.encode(query,StandardCharsets.UTF_8)
                +"&start="+start.getEpochSecond()+"&end="+end.getEpochSecond()+"&step=30&timeout=2s&limit=1");
            var future=http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),
                info -> new ComposeManagedAdapter.BoundedBodySubscriber(65536));
            HttpResponse<byte[]> response;
            try { response=future.get(3,TimeUnit.SECONDS); } catch (Exception e) { future.cancel(true); throw e; }
            if (response.body().length>65536) { quality=EvidenceEnvelope.Quality.TRUNCATED; limitation="metric response byte cap reached"; }
            else if (response.statusCode()!=200) { quality=EvidenceEnvelope.Quality.ERROR; limitation="metric source HTTP status="+response.statusCode(); }
            else {
                var body=JSON.readTree(response.body());
                if (!"success".equals(body.path("status").asText()) || !"matrix".equals(body.path("data").path("resultType").asText()))
                    throw new IllegalArgumentException("metric response contract mismatch");
                var result=body.path("data").path("result");
                if (!result.isArray() || result.size()>1) throw new IllegalArgumentException("metric series cap exceeded");
                if (body.path("warnings").size()>0 || body.path("infos").size()>0) {
                    quality=EvidenceEnvelope.Quality.MISSING;
                    String warning=LogSanitizer.sanitizeAll(body.path("warnings").toString()+body.path("infos")).text();
                    limitation="metric query reported partial/warning results: "+warning.substring(0,Math.min(280,warning.length()));
                }
                if (result.isEmpty()) { quality=EvidenceEnvelope.Quality.MISSING; limitation="metric series missing; no history inferred"; }
                else {
                    var series=result.get(0); var labels=series.path("metric");
                    if (!target.project().equals(labels.path("container_label_com_docker_compose_project").asText())
                            || !target.service().equals(labels.path("container_label_com_docker_compose_service").asText())
                            || !labels.path("id").asText().contains(target.containerId()))
                        throw new IllegalArgumentException("metric container identity mismatch");
                    var values=series.path("values");
                    if (!values.isArray() || values.size()>240) throw new IllegalArgumentException("metric sample cap exceeded");
                    Instant previous=null;
                    for (var value:values) {
                        if (!value.isArray() || value.size()!=2 || !value.get(0).isNumber()) throw new IllegalArgumentException("invalid metric sample");
                        var timestamp=new java.math.BigDecimal(value.get(0).asText());
                        Instant at=Instant.ofEpochMilli(timestamp.multiply(java.math.BigDecimal.valueOf(1000)).longValueExact());
                        double number=Double.parseDouble(value.get(1).asText());
                        if (!Double.isFinite(number) || number<0 || at.isBefore(start) || at.isAfter(end) || previous!=null && !at.isAfter(previous))
                            throw new IllegalArgumentException("invalid metric time/value");
                        samples.add(new EvidenceEnvelope.MetricSample(at,"container_memory_working_set_bytes",number,"bytes")); previous=at;
                    }
                    if (samples.isEmpty() || samples.getLast().at().isBefore(end.minus(app.evidenceTtl()))) {
                        quality=EvidenceEnvelope.Quality.MISSING; limitation="metric series empty/stale; no current trend inferred";
                    }
                }
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        catch (Exception e) { quality=EvidenceEnvelope.Quality.ERROR; limitation="metric source failed: "+e.getClass().getSimpleName(); samples.clear(); }
        var payload=new EvidenceEnvelope.Payload(new EvidenceEnvelope.Collection(EvidenceEnvelope.Source.PROMETHEUS,quality,start,end,
            clock.instant(),"bytes","range-step-30s",limitation,null),null,List.of(),samples);
        return new Observation(app.targetId(),app.composeProject(),app.service(),Probe.METRICS,end,Status.UNKNOWN,
            "Memory samples="+samples.size()+"; "+limitation,payload);
    }
    @Override public void close() { http.close(); }
}
