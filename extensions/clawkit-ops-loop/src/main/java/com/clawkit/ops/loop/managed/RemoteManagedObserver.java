package com.clawkit.ops.loop.managed;

import com.clawkit.ops.loop.OpsReadSession;
import com.clawkit.ops.mcp.LogSanitizer;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.clawkit.ops.loop.managed.EvidenceEnvelope.Collection;
import static com.clawkit.ops.loop.managed.ManagedObserver.*;
import static com.clawkit.ops.loop.managed.EvidenceEnvelope.*;

/** Bridges attested OpsReadSession facts into the existing diagnostic/controller contract. */
public final class RemoteManagedObserver implements ManagedObserver {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RemoteObservationBinding binding;
    private final OpsReadSession session;
    private final Clock clock;
    private final ManagedChangeStore changes;
    @FunctionalInterface interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
    private final Sleeper sleeper;
    private boolean closed;
    public RemoteManagedObserver(RemoteObservationBinding binding, OpsReadSession session, Clock clock,
                                 ManagedChangeStore changes) {
        this(binding,session,clock,changes,d -> Thread.sleep(Math.max(1,d.toMillis())));
    }
    RemoteManagedObserver(RemoteObservationBinding binding,OpsReadSession session,Clock clock,ManagedChangeStore changes,Sleeper sleeper) {
        this.binding = Objects.requireNonNull(binding); this.session = Objects.requireNonNull(session);
        this.clock = Objects.requireNonNull(clock); this.changes = changes;
        this.sleeper=Objects.requireNonNull(sleeper);
        requireSession();
    }
    private void requireSession() {
        if (closed || !session.isReady() || !binding.targetId().equals(session.targetId()))
            throw new IllegalStateException("remote session target or lifecycle differs");
    }
    @Override public Observation observe(ManagedApplication app, Probe probe) throws Exception {
        binding.validate(app); requireSession();
        try {
            return switch (probe) {
                case SERVICE -> service(app);
                case HEALTH -> http(app, probe, binding.health());
                case BUSINESS -> http(app, probe, binding.business());
                case DEPENDENCIES -> dependencies(app);
                case LOGS -> logs(app);
                case RESOURCES -> resources(app);
                case CHANGES -> changes(app);
                case METRICS -> metrics(app);
            };
        } catch (IOException | IllegalArgumentException | DateTimeException e) {
            return absent(app, probe, Quality.ERROR, "远端来源无有效事实：" + probe + "（" + e.getClass().getSimpleName() + "）");
        }
    }
    private record Frame(JsonNode data, Instant observed, Instant collected, boolean truncated) {}
    private Frame read(String tool, String target, Map<String,Object> args) throws IOException {
        requireSession();
        var request = JSON.createObjectNode(); args.forEach((k,v) -> request.set(k, JSON.valueToTree(v)));
        var result = session.callTool(tool, request);
        requireSession();
        if (result == null || result.isError() || result.text() == null
                || result.text().getBytes(StandardCharsets.UTF_8).length > 65_536)
            throw new IOException("bounded successful MCP text required");
        var body = JSON.readTree(result.text());
        if (body == null || !body.isObject() || !tool.equals(body.path("tool").asText()) || !target.equals(body.path("target").asText())
                || !body.path("success").isBoolean() || !body.path("success").asBoolean()
                || !body.path("current").isBoolean() || !body.path("current").asBoolean()
                || !body.path("data").isObject() || !body.path("audit").path("truncated").isBoolean())
            throw new IOException("MCP source identity or completeness differs");
        Instant observed, collected;
        try { observed = Instant.parse(body.path("observedAt").asText()); collected = Instant.parse(body.path("collectedAt").asText()); }
        catch (RuntimeException e) { throw new IOException("valid remote source time required", e); }
        Instant now=clock.instant();
        if (observed.isAfter(collected) || collected.isAfter(now.plusSeconds(5)))
            throw new IOException("remote source window or clock differs");
        // Keep source time unchanged. Facts cannot enter the strict ledger until local time reaches collection.
        if(collected.isAfter(now)) {
            try { sleeper.sleep(Duration.between(now,collected).plusMillis(1)); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("remote clock wait interrupted",e); }
            if(clock.instant().isBefore(collected)) throw new IOException("local clock did not reach remote collection time");
            requireSession();
        }
        return new Frame(body.get("data"), observed, collected, body.path("audit").path("truncated").asBoolean());
    }
    private Frame container() throws IOException {
        var f = read("container_status", binding.service(), Map.of("service", binding.service()));
        containerIdentity(f); return f;
    }
    private void containerIdentity(Frame frame) throws IOException {
        if (!binding.service().equals(frame.data().path("service").asText())
                || !binding.containerId().equals(frame.data().path("containerId").asText()))
            throw new IOException("observed container differs from registered read-only source");
    }
    private Observation service(ManagedApplication app) throws IOException {
        var f = container(); var state = f.data().path("state");
        Status status = Status.UNKNOWN;
        if (state.path("Restarting").isBoolean() && state.path("Restarting").asBoolean()) status = Status.RESTARTING;
        else if (state.path("Running").isBoolean()) status = state.path("Running").asBoolean() ? Status.RUNNING : Status.STOPPED;
        return observation(app, Probe.SERVICE, status, "远端容器 " + state.path("Status").asText("unknown"), f,
                f.observed(), f.observed(), null, List.of(), List.of(), "仅登记的观测身份；环境为本地声明，远端项目/daemon 未证明；短 ID 不取得修复资格");
    }
    private Observation http(ManagedApplication app, Probe probe, RemoteObservationBinding.Endpoint endpoint) throws IOException {
        if (endpoint == null) return absent(app, probe, Quality.MISSING, "未登记远端" + probe + "来源，不推断健康");
        var f = read("http_probe", endpoint.name(), Map.of("endpoint", endpoint.name()));
        if (!endpoint.name().equals(f.data().path("endpoint").asText()) || !endpoint.uri().toString().equals(f.data().path("uri").asText())
                || !f.data().path("statusCode").isIntegralNumber() || !f.data().path("body").isTextual())
            throw new IOException("registered endpoint differs or predicate facts missing");
        int code = f.data().get("statusCode").intValue();
        boolean healthy = code >= 200 && code < 300 && f.data().get("body").textValue().contains(endpoint.marker());
        return observation(app, probe, healthy ? Status.HEALTHY : Status.UNHEALTHY,
                "远端 HTTP " + code + "; 登记响应条件=" + healthy, f, f.observed(), f.observed(), null, List.of(), List.of(), "不跟随其他端点；指标不能替代业务判定");
    }
    private Observation dependencies(ManagedApplication app) throws IOException {
        if (binding.dependencies().isEmpty()) return absent(app, Probe.DEPENDENCIES, Quality.MISSING, "尚未登记远端依赖关系");
        Frame last = null; Instant start = null, end = null; boolean unhealthy = false, unknown = false;
        for (String dependency : binding.dependencies()) {
            var f = read("service_status", dependency, Map.of("service", dependency)); last = f;
            if (!dependency.equals(f.data().path("service").asText()) || !f.data().path("containers").isArray())
                throw new IOException("dependency source identity differs");
            if (f.truncated()) throw new IOException("dependency source truncated");
            var list = f.data().path("containers");
            if (list.size() != 1) unknown = true;
            else { var state = list.get(0);
                if (!"running".equals(state.path("State").asText())) unhealthy = true;
                else if ("unhealthy".equals(state.path("Health").asText())) unhealthy = true;
                else if (!"healthy".equals(state.path("Health").asText())) unknown = true;
            }
            start = start == null || f.observed().isBefore(start) ? f.observed() : start;
            end = end == null || f.observed().isAfter(end) ? f.observed() : end;
        }
        return observation(app, Probe.DEPENDENCIES, unhealthy ? Status.UNHEALTHY : unknown ? Status.UNKNOWN : Status.HEALTHY,
                "登记依赖检查：" + binding.dependencies(), last, start, end, null, List.of(), List.of(), "仅用户声明依赖；没有健康字段时保留未知");
    }
    private Observation logs(ManagedApplication app) throws IOException {
        var f = read("logs", binding.service(), Map.of("service", binding.service(), "windowSeconds", 300, "tail", 50));
        containerIdentity(f);
        Instant start = Instant.parse(f.data().path("since").asText()), end = Instant.parse(f.data().path("until").asText());
        if (start.isAfter(end) || end.isAfter(f.collected()) || Duration.between(start,end).compareTo(Duration.ofSeconds(300)) > 0)
            throw new IOException("log source window differs");
        if (!f.data().path("text").isTextual()) throw new IOException("log text missing");
        var safe = LogSanitizer.sanitizeAll(f.data().get("text").textValue()).text();
        boolean clip = safe.length() > 1200;
        var bounded = new Frame(f.data(), end, f.collected(), f.truncated() || clip);
        return observation(app, Probe.LOGS, Status.UNKNOWN, safe.substring(0, Math.min(1200,safe.length())), bounded,
                start, end, null, List.of(), List.of(), "远端五分钟/五十行窗口，输出脱敏有界；截断不补造完整日志");
    }
    private Observation resources(ManagedApplication app) throws IOException {
        var stats = read("container_resources", binding.service(), Map.of("service", binding.service())); containerIdentity(stats);
        var state = container(); var node = state.data().path("state");
        if (!node.path("OOMKilled").isBoolean() || !node.path("ExitCode").isIntegralNumber())
            return absent(app, Probe.RESOURCES, Quality.MISSING, "远端缺少 OOM/退出码，不补成 false/0");
        Instant finished = null;
        if (!node.path("FinishedAt").asText().isBlank()) finished = Instant.parse(node.path("FinishedAt").asText());
        var facts = new ResourceFacts(node.get("OOMKilled").booleanValue(), node.get("ExitCode").intValue(), finished, null, null);
        Instant start = stats.observed().isBefore(state.observed()) ? stats.observed() : state.observed();
        Instant end = stats.observed().isAfter(state.observed()) ? stats.observed() : state.observed();
        Instant collected = stats.collected().isAfter(state.collected()) ? stats.collected() : state.collected();
        var joined = new Frame(stats.data(), end, collected, stats.truncated() || state.truncated());
        String memory = stats.data().path("MemUsage").asText("未返回可规范化的内存字节数");
        return observation(app, Probe.RESOURCES, Status.UNKNOWN, "OOM=" + facts.oomKilled() + "; exit=" + facts.exitCode() + "; memory=" + memory,
                joined, start, end, facts, List.of(), List.of(), "同一登记容器的 inspect 与 stats 快照；未转换内存单位，不代表趋势");
    }
    private Observation changes(ManagedApplication app) throws IOException {
        if (changes == null) return absent(app, Probe.CHANGES, Quality.MISSING, "未配置人工/CI 变更来源");
        Instant end = clock.instant(), start = end.minusSeconds(900); var records = changes.within(app,start,end);
        var collection = new Collection(Source.CHANGE_IMPORT, records.isEmpty() ? Quality.MISSING : Quality.COMPLETE,
                start,end,end,"change","imported-window","人工/CI 导入，无记录不等于没有变更",null);
        return new Observation(app.targetId(),app.composeProject(),app.service(),Probe.CHANGES,end,Status.UNKNOWN,
                "已登记变更记录 " + records.size(),new Payload(collection,null,records,List.of()));
    }
    private Observation metrics(ManagedApplication app) throws IOException {
        if (binding.metrics() == null) return absent(app, Probe.METRICS, Quality.MISSING, "未登记远端指标来源");
        var endpoint = binding.metrics(); var f = read("business_metrics",endpoint.name(),Map.of("endpoint",endpoint.name()));
        if (!endpoint.name().equals(f.data().path("endpoint").asText())) throw new IOException("metrics source identity differs");
        var values = new ArrayList<MetricSample>();
        for (String name : List.of("requestsTotal","errorsTotal","active","idle","pending","max")) {
            var value = f.data().get(name); if (value != null && value.isNumber()) values.add(new MetricSample(f.observed(),name,value.doubleValue(),"count"));
        }
        if (values.isEmpty()) return absent(app, Probe.METRICS,Quality.MISSING,"指标来源没有可识别的数值，保留缺口");
        return observation(app,Probe.METRICS,Status.UNKNOWN,"远端指标快照 " + values.size(),f,f.observed(),f.observed(),null,List.of(),values,"快照不是趋势；累计错误不自动代表当前故障");
    }
    private Observation observation(ManagedApplication app, Probe probe, Status status, String detail, Frame f,
            Instant start, Instant end, ResourceFacts resources, List<ChangeRecord> records, List<MetricSample> metrics, String limitation) {
        var collection = new Collection(Source.SSH_MCP, f.truncated() ? Quality.TRUNCATED : Quality.COMPLETE,
                start,end,f.collected(),"source-facts","remote-snapshot",limitation,null);
        String safe = LogSanitizer.sanitizeAll(detail).text();
        if (safe.length() > 1200) throw new IllegalArgumentException("normalized remote fact exceeds limit");
        return new Observation(app.targetId(),app.composeProject(),app.service(),probe,end,status,safe,new Payload(collection,resources,records,metrics));
    }
    private Observation absent(ManagedApplication app, Probe probe, Quality quality, String detail) {
        Instant now=clock.instant();
        return new Observation(app.targetId(),app.composeProject(),app.service(),probe,now,Status.UNKNOWN,detail,
                Payload.snapshot(Source.SSH_MCP,quality,now,detail));
    }
    @Override public void close() { if (!closed) { closed = true; session.close(); } }
}
