package com.clawkit.ops.delivery.managed;

import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.IsolatedComposeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PrometheusMetricSourceTest {
    static final Instant NOW=Instant.parse("2026-09-30T10:00:00Z");
    static final String ID="a".repeat(64);
    @Test void fixedTemplatePreservesMissingWarningsStalenessAndBadIdentity() throws Exception {
        var response=new AtomicReference<String>(); var query=new AtomicReference<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/query_range",exchange -> {
            query.set(URLDecoder.decode(exchange.getRequestURI().getQuery(),StandardCharsets.UTF_8));
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try (var source=new PrometheusMetricSource(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),target(),Clock.fixed(NOW,ZoneOffset.UTC))) {
            response.set(body(ID,0,false,"1024"));
            var fact=source.observe(app()); assertThat(fact.payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.COMPLETE);
            assertThat(fact.payload().metrics()).singleElement().satisfies(m -> assertThat(m.value()).isEqualTo(1024));
            assertThat(query.get()).contains(ID,"container_label_com_docker_compose_service=\"api\"","step=30","timeout=2s","limit=1");
            response.set(body(ID,0,true,"1024"));
            assertThat(source.observe(app()).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.MISSING);
            response.set(body(ID,200,false,"1024"));
            assertThat(source.observe(app()).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.MISSING);
            response.set(body("b".repeat(64),0,false,"1024"));
            assertThat(source.observe(app()).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.ERROR);
            response.set(body(ID,0,false,"NaN"));
            assertThat(source.observe(app()).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.ERROR);
            response.set("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}");
            assertThat(source.observe(app()).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.MISSING);
            response.set("x".repeat(65537));
            assertThat(source.observe(app()).payload().collection().quality()).isEqualTo(EvidenceEnvelope.Quality.TRUNCATED);
        } finally { server.stop(0); }
        assertThatThrownBy(() -> new ManagedMetricStore.Configuration("demo",1,URI.create("http://remote.example:9090"),NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }
    private static String body(String id,int age,boolean warning,String value) throws Exception {
        var json=new ObjectMapper(); var body=json.createObjectNode().put("status","success");
        if(warning) body.putArray("warnings").add("partial results");
        var series=body.putObject("data").put("resultType","matrix").putArray("result").addObject();
        series.putObject("metric").put("container_label_com_docker_compose_project",target().project())
            .put("container_label_com_docker_compose_service","api").put("id","/docker/"+id);
        series.putArray("values").addArray().add(NOW.minusSeconds(age).getEpochSecond()).add(value); return body.toString();
    }
    private static IsolatedComposeClient.Target target() { return new IsolatedComposeClient.Target("default","daemon","unix:///var/run/docker.sock","compose.yaml",ID,
        "clawkit-autonomy-metrics-test","api",ID,Map.of()); }
    private static ManagedApplication app() { return new ManagedApplication("demo","local-isolated",target().project(),"api",1,true,ManagedApplication.DesiredState.RUNNING,
        null,URI.create("http://127.0.0.1:18080/health"),URI.create("http://127.0.0.1:18080/"),"accepted",Duration.ofSeconds(5),Duration.ofSeconds(90)); }
}
