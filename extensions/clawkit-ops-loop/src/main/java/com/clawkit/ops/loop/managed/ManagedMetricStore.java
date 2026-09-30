package com.clawkit.ops.loop.managed;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;

/** Explicit local metric-source configuration; it is not an action authorization. */
public final class ManagedMetricStore {
    public record Configuration(String applicationId,long applicationVersion,URI endpoint,Instant at) {
        public Configuration {
            ManagedApplication.identifier(applicationId);
            if (applicationVersion<1 || at==null) throw new IllegalArgumentException("metric registration identity required");
            if (endpoint!=null && (!"http".equals(endpoint.getScheme())
                    || endpoint.getHost()==null || !java.util.Set.of("localhost","127.0.0.1","[::1]").contains(endpoint.getHost())
                    || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null
                    || endpoint.getPort()==0 || endpoint.getPort()>65535 || endpoint.getPath().contains("..")))
                throw new IllegalArgumentException("metrics need a registered loopback HTTP endpoint without credentials/query");
        }
    }
    private final Path root;
    private final ManagedControlStore files;
    public ManagedMetricStore(Path root) throws IOException { this.root=root.toAbsolutePath().normalize(); files=new ManagedControlStore(this.root); }
    public void configure(ManagedApplication app,URI endpoint,Instant at) throws Exception {
        files.locked(() -> { files.write("source.json",new Configuration(app.id(),app.version(),endpoint,at)); return null; });
    }
    public Configuration read(ManagedApplication app) throws IOException {
        Path path=root.resolve("source.json"); if (!Files.exists(path)) return null;
        byte[] bytes; try (var input=Files.newInputStream(path)) { bytes=input.readNBytes(4097); }
        if (bytes.length>4096) throw new IOException("metric registration exceeds size limit");
        var record=ManagedContracts.JSON.readValue(bytes,Configuration.class);
        if (!app.id().equals(record.applicationId()) || app.version()!=record.applicationVersion()) throw new IOException("metric source identity/version differs");
        return record;
    }
}
