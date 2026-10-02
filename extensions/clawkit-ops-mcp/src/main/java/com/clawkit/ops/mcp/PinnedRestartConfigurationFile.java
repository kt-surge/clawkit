package com.clawkit.ops.mcp;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Production loader: Linux root owns the configuration, its ancestors and the persistent store. */
public final class PinnedRestartConfigurationFile implements PinnedRestartService.ConfigurationSource {
    private final Path path;
    public PinnedRestartConfigurationFile(Path path) {
        if(path==null || !path.isAbsolute()) throw new IllegalArgumentException("absolute root-owned configuration path required");
        this.path=path.normalize();
    }
    @Override public PinnedRestartContract.Configuration read() throws Exception {
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux")) throw new IOException("production gateway requires Linux ownership checks");
        trusted(path,false);
        PinnedRestartContract.Configuration value;
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes(32769); if(bytes.length>32768) throw new IOException("configuration size limit exceeded");
            value=PinnedRestartContract.JSON.readValue(bytes,PinnedRestartContract.Configuration.class);
        }
        trusted(Path.of(value.stateDirectory()),true);
        return value;
    }
    private static void trusted(Path path,boolean directory) throws IOException {
        if(!path.isAbsolute() || (directory ? !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)))
            throw new IOException("required trusted file/directory missing");
        for(Path entry=path;entry!=null;entry=entry.getParent()) {
            if(Files.isSymbolicLink(entry) || ((Number)Files.getAttribute(entry,"unix:uid",LinkOption.NOFOLLOW_LINKS)).longValue()!=0)
                throw new IOException("gateway path must be root owned without symbolic links");
            var permissions=Files.getPosixFilePermissions(entry,LinkOption.NOFOLLOW_LINKS);
            if(permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.OTHERS_WRITE))
                throw new IOException("gateway path cannot be writable by other identities");
        }
    }
}
