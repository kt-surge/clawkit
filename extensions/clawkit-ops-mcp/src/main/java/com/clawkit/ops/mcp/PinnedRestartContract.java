package com.clawkit.ops.mcp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Versioned fixed action contract. Configuration belongs to the operator, never to model arguments. */
public final class PinnedRestartContract {
    public static final int VERSION=2;
    public static final ObjectMapper JSON=new ObjectMapper().registerModule(new JavaTimeModule())
        .configure(MapperFeature.ALLOW_COERCION_OF_SCALARS,false)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private PinnedRestartContract() {}
    public record Target(String context,String daemonId,String endpoint,String composeFile,String composeHash,
                         String project,String service,String containerId,Map<String,String> dependencies,
                         Map<String,IsolatedComposeClient.MountPin> mounts) {
        public Target {
            token(context); token(project); token(service); digest(composeHash); digest(containerId);
            if (!"order-api".equals(service) || daemonId==null || daemonId.isBlank() || daemonId.length()>256
                    || endpoint==null || !(endpoint.startsWith("unix:///") || endpoint.startsWith("npipe:////./pipe/"))
                    || composeFile==null || !Path.of(composeFile).isAbsolute())
                throw new IllegalArgumentException("fixed order-api/local daemon/absolute Compose identity required");
            dependencies=Map.copyOf(dependencies); mounts=Map.copyOf(mounts);
            if(dependencies.size()>2 || mounts.size()>16 || dependencies.containsKey(service))
                throw new IllegalArgumentException("bounded distinct dependency and configuration pins required");
            dependencies.forEach((name,id) -> { token(name); digest(id); });
            mounts.forEach((destination,pin) -> {
                if(!destination.startsWith("/") || pin==null || pin.source()==null || pin.source().isBlank()
                        || pin.hostPath()==null || !Path.of(pin.hostPath()).isAbsolute())
                    throw new IllegalArgumentException("absolute read-only bind mount pins required");
                digest(pin.contentHash());
            });
        }
        public String fingerprint() { return hash(this); }
    }
    public record Configuration(int schemaVersion,Target target,long grantVersion,Instant issuedAt,Instant expiresAt,
                                boolean enabled,boolean statelessReviewed,String stateDirectory) {
        public Configuration {
            Objects.requireNonNull(target); Objects.requireNonNull(issuedAt); Objects.requireNonNull(expiresAt);
            if(schemaVersion!=VERSION || grantVersion<1 || grantVersion>Integer.MAX_VALUE || !issuedAt.isBefore(expiresAt)
                    || Duration.between(issuedAt,expiresAt).compareTo(Duration.ofMinutes(30))>0
                    || !statelessReviewed || stateDirectory==null || !Path.of(stateDirectory).isAbsolute())
                throw new IllegalArgumentException("V2 reviewed stateless scope, <=30-minute grant and absolute state directory required");
        }
        public String fingerprint() { return hash(this); }
        public boolean activeAt(Instant now) { return enabled && !now.isBefore(issuedAt) && now.isBefore(expiresAt); }
    }
    public record Request(int schemaVersion,String requestId,String incidentId,String targetHash,String configurationHash,
                          long grantVersion,Instant grantExpiresAt) {
        public Request {
            PinnedRestartContract.requestId(requestId); PinnedRestartContract.incidentId(incidentId); digest(targetHash); digest(configurationHash);
            if(schemaVersion!=VERSION || grantVersion<1 || grantVersion>Integer.MAX_VALUE || grantExpiresAt==null)
                throw new IllegalArgumentException("V2 request and explicit grant required");
        }
        public static Request of(String requestId,String incidentId,Configuration scope) {
            return new Request(VERSION,requestId,incidentId,scope.target().fingerprint(),scope.fingerprint(),scope.grantVersion(),scope.expiresAt());
        }
        public String fingerprint() { return hash(this); }
        public boolean matches(Configuration scope) {
            return targetHash.equals(scope.target().fingerprint()) && configurationHash.equals(scope.fingerprint())
                && grantVersion==scope.grantVersion() && grantExpiresAt.equals(scope.expiresAt());
        }
    }
    public enum Status { REJECTED, DISPATCH_REPORTED, UNKNOWN }
    public record Receipt(int schemaVersion,Request request,String requestHash,Status status,String code,Instant at) {
        public Receipt {
            Objects.requireNonNull(request); Objects.requireNonNull(status); Objects.requireNonNull(at);
            digest(requestHash); token(code);
            if(schemaVersion!=VERSION || !requestHash.equals(request.fingerprint()))
                throw new IllegalArgumentException("receipt does not bind the exact request");
        }
    }
    public record Intent(int schemaVersion,Request request,String requestHash,Instant at) {
        public Intent {
            Objects.requireNonNull(request); Objects.requireNonNull(at); digest(requestHash);
            if(schemaVersion!=VERSION || !requestHash.equals(request.fingerprint())) throw new IllegalArgumentException("invalid dispatch intent");
        }
    }
    public static Receipt receipt(Request request,Status status,String code,Instant now) {
        return new Receipt(VERSION,request,request.fingerprint(),status,code,now);
    }
    public static void requestId(String value) {
        if(value==null || !value.matches("att-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("stable client Attempt id required");
    }
    private static void incidentId(String value) {
        if(value==null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}")) throw new IllegalArgumentException("bounded incident identity required");
    }
    private static void token(String value) {
        if(value==null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) throw new IllegalArgumentException("invalid contract token");
    }
    private static void digest(String value) {
        if(value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("full SHA-256/container identity required");
    }
    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value))); }
        catch(Exception e) { throw new IllegalStateException("cannot hash fixed contract",e); }
    }
}
