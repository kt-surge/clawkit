package com.clawkit.ops.delivery.managed;

import com.clawkit.ops.loop.managed.ManagedTriggerStore;
import com.sun.net.httpserver.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.*;
import java.time.Clock;
import java.util.concurrent.*;

/** Explicit local receiver, model-free. Credentials are only checked, never persisted or returned. */
public final class AlertmanagerReceiver implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16),r -> {
        var thread=new Thread(r,"clawkit-alert-receiver"); thread.setDaemon(true); return thread; },new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r -> { var t=new Thread(r,"clawkit-alert-deadline"); t.setDaemon(true); return t; });
    public AlertmanagerReceiver(ManagedTriggerStore store,Clock clock,int port) throws IOException {
        if(port<0 || port>65535) throw new IllegalArgumentException("valid local receiver port required");
        var config=store.config(); if(config==null || !config.enabled()) throw new IllegalArgumentException("bind an alert source before listening");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),16); server.setExecutor(workers);
        var input=new AlertmanagerInput(store,clock);
        server.createContext("/alertmanager",exchange -> {
            var timeout=deadlines.schedule(exchange::close,5,TimeUnit.SECONDS);
            try {
                if(!exchange.getRequestURI().getPath().equals("/alertmanager") || exchange.getRequestURI().getQuery()!=null || !exchange.getRequestMethod().equals("POST")) { reply(exchange,405,"POST /alertmanager required"); return; }
                var auth=exchange.getRequestHeaders().get("Authorization"); String value=auth==null || auth.size()!=1 ? "" : auth.getFirst();
                if(!value.startsWith("Bearer ") || !store.authenticated(value.substring(7))) { reply(exchange,401,"source authentication failed"); return; }
                String contentType=exchange.getRequestHeaders().getFirst("Content-Type");
                if(contentType==null || !contentType.split(";",2)[0].trim().equalsIgnoreCase("application/json")) { reply(exchange,415,"application/json required"); return; }
                byte[] bytes=exchange.getRequestBody().readNBytes(65537);
                if(bytes.length>65536) { store.reject("PAYLOAD_TOO_LARGE",AlertmanagerInput.hash(bytes)); reply(exchange,413,"payload byte cap exceeded"); return; }
                var result=input.accept(bytes); byte[] json=new ObjectMapper().writeValueAsBytes(result);
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(result.pendingReview()>0 ? 202 : 200,json.length);
                exchange.getResponseBody().write(json);
            } catch(IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) { reply(exchange,400,"invalid source contract; local rejection retained"); }
            catch(Exception e) { reply(exchange,503,"local intake unavailable; retry later"); }
            finally { timeout.cancel(false); exchange.close(); }
        }); server.start();
    }
    public URI endpoint() { return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/alertmanager"); }
    private static void reply(HttpExchange exchange,int code,String text) throws IOException {
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(code,bytes.length); exchange.getResponseBody().write(bytes);
    }
    @Override public void close() { server.stop(0); workers.shutdownNow(); deadlines.shutdownNow(); }
}
