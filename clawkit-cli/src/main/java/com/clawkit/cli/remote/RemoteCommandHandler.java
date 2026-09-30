package com.clawkit.cli.remote;

import com.clawkit.cli.ConsoleRenderer;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolMount;
import com.clawkit.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler for {@code /remote} slash commands using deterministic parsing.
 *
 * <p>All operations are deterministic — no LLM involvement.
 * Uses {@link RemoteCommandParser} for tokenization.
 */
public class RemoteCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(RemoteCommandHandler.class);

    private final RemoteConnectionService service;
    private final RemoteTargetStore store;
    private final ToolRegistry registry;
    private final RemoteOnboardingService onboarding;
    private final RemoteDoctorService doctorService;

    public RemoteCommandHandler(RemoteConnectionService service, RemoteTargetStore store,
                                 ToolRegistry registry, RemoteOnboardingService onboarding,
                                 RemoteDoctorService doctorService) {
        this.service = service;
        this.store = store;
        this.registry = registry;
        this.onboarding = onboarding;
        this.doctorService = doctorService;
    }

    /** Handle a /remote command. Returns true if recognized. */
    public boolean handle(String arguments) {
        var cmd = RemoteCommandParser.parse(arguments);
        try {
            return switch (cmd.subCommand()) {
                case LIST -> { cmdList(); yield true; }
                case STATUS -> { cmdStatus(); yield true; }
                case INSPECT, SHOW -> { cmdInspect(cmd.arg(0)); yield true; }
                case ADD -> { cmdAdd(cmd); yield true; }
                case REMOVE -> { cmdRemove(cmd.arg(0)); yield true; }
                case CONNECT -> { cmdConnect(cmd.arg(0)); yield true; }
                case DISCONNECT -> { cmdDisconnect(); yield true; }
                case DOCTOR -> { cmdDoctor(cmd); yield true; }
                case TOOLS -> { cmdTools(); yield true; }
                case HELP -> { cmdHelp(); yield true; }
                case UNKNOWN -> { cmdHelp(); yield true; }
            };
        } catch (Exception e) {
            println("  [ERROR] " + e.getMessage());
            log.warn("[remote-cmd] failed: {}", e.getMessage());
            return true;
        }
    }

    // ── Sub-commands ──────────────────────────────────────────────────

    private void cmdStatus() {
        var snapshot = service.lastSnapshot();
        if (snapshot.isEmpty()) {
            println("  当前没有已连接的服务器。");
            List<String> ids = store.list();
            if (ids.isEmpty()) {
                println("  还没有登记服务器。可输入 /remote add --from-ssh <SSH别名> 添加。");
                return;
            }
            println("  已登记的服务器：");
            for (String id : ids) {
                println("    - " + id + " (未连接)");
            }
            if (ids.size() == 1) {
                println("  下一步: 输入 [连接 " + ids.getFirst()
                    + "] 进行连接, 或输入 /remote doctor " + ids.getFirst() + " 检查连接。");
            } else {
                println("  下一步: 输入 [连接 <服务器名称>],"
                    + " 或用 /remote doctor <服务器名称> 检查连接。");
            }
            return;
        }
        var s = snapshot.get();
        println("  服务器:    " + s.targetId() + " (只读)");
        println("  连接状态:  " + displayState(s.state()));
        if (s.attestation() != null) {
            var a = s.attestation();
            println("  能力范围:  " + a.capabilityProfile());
            if (!s.mountedTools().isEmpty()) {
                println("  可用工具 (" + s.mountedTools().size() + " 个): "
                    + String.join(", ", s.mountedTools()));
            }
            println("  耗时:      连接 " + a.connectLatencyMs()
                + " 毫秒, 校验 " + a.attestationLatencyMs() + " 毫秒");
        }
        if (s.error() != null) {
            println("  错误:      " + s.error().code() + " - " + s.error().safeMessage());
        }
    }

    private static String displayState(com.clawkit.tools.remote.RemoteConnectionState state) {
        return switch (state) {
            case DISCONNECTED -> "未连接";
            case CONNECTING -> "正在连接";
            case ATTESTING -> "正在校验服务器";
            case READY -> "连接正常";
            case DEGRADED -> "连接可用，但部分检查失败";
            case FAILED -> "连接失败";
            case CLOSED -> "已关闭";
        };
    }

    private void cmdList() {
        List<String> ids = store.list();
        if (ids.isEmpty()) {
            println("  No registered targets.");
            println("  Use /remote add --from-ssh <alias> to register.");
            return;
        }
        println("  Registered targets:");
        for (String id : ids) {
            String active = store.isActive(id) ? " [ACTIVE]" : "";
            // Determine profile from either v1 or v2
            String profile = "?";
            var v1 = store.get(id);
            if (v1.isPresent()) {
                profile = v1.get().attestation().expectedCapabilityProfile();
            } else {
                var v2 = store.getRegistration(id);
                if (v2.isPresent()) {
                    var m = RemoteProfileCatalog.lookup(v2.get().profileManifestId());
                    profile = m.map(RemoteProfileManifest::capabilityProfile).orElse("?");
                }
            }
            println("    - " + id + active + "  " + profile);
        }
    }

    private void cmdInspect(String targetId) {
        if (targetId.isEmpty()) {
            // Show current connection details
            var snapshot = service.lastSnapshot();
            if (snapshot.isEmpty()) {
                println("  No active connection. Specify a targetId or connect first.");
                return;
            }
            var s = snapshot.get();
            println("  target:       " + s.targetId());
            println("  state:        " + s.state());
            println("  generation:   " + s.generation());
            if (s.attestation() != null) {
                var a = s.attestation();
                println("  server:       " + a.serverName());
                println("  protocol:     " + a.protocolVersion());
                println("  probe:        v" + a.probeVersion());
                println("  profile:      " + a.capabilityProfile());
                println("  toolSetHash:  " + a.advertisedToolSetHash());
                println("  contractHash: " + a.computedToolContractHash());
                println("  connect:      " + a.connectLatencyMs() + "ms");
                println("  attest:       " + a.attestationLatencyMs() + "ms");
            }
            return;
        }
        // Show stored target details
        var v1 = store.get(targetId);
        if (v1.isPresent()) {
            var c = v1.get();
            println("  targetId:    " + c.targetId());
            println("  schema:      v" + c.schemaVersion());
            println("  host:        " + c.endpoint().host() + ":" + c.endpoint().port());
            println("  user:        " + c.endpoint().user());
            println("  profile:     " + c.attestation().expectedCapabilityProfile());
            println("  server:      " + c.attestation().expectedServerName());
            println("  active:      " + store.isActive(targetId));
            return;
        }
        var v2 = store.getRegistration(targetId);
        if (v2.isPresent()) {
            var r = v2.get();
            println("  targetId:    " + r.targetId());
            println("  schema:      v" + r.schemaVersion());
            println("  connection:  " + r.connection().type());
            switch (r.connection()) {
                case OpenSshAliasReference a ->
                    println("  alias:       " + a.alias() + " (user=" + a.remoteUser() + ")");
                case LegacyExplicitEndpointReference ep ->
                    println("  host:        " + ep.host() + ":" + ep.port());
            }
            var m = RemoteProfileCatalog.lookup(r.profileManifestId());
            println("  profile:     " + m.map(RemoteProfileManifest::capabilityProfile).orElse("?"));
            println("  active:      " + store.isActive(targetId));
            return;
        }
        println("  Target not found: " + targetId);
    }

    private void cmdAdd(RemoteCommandParser.ParsedCommand cmd) {
        String fromSsh = cmd.option("from-ssh");
        String asId = cmd.option("as");
        String configFile = cmd.option("config");
        boolean replace = cmd.hasOption("replace");

        if (fromSsh != null && !fromSsh.isEmpty()) {
            cmdAddFromSsh(fromSsh, asId, replace);
            return;
        }

        if (configFile != null && !configFile.isEmpty()) {
            String targetId = cmd.arg(0);
            cmdAddLegacy(targetId, configFile, replace);
            return;
        }

        // Interactive discovery
        cmdAddInteractive();
    }

    private void cmdAddFromSsh(String alias, String asId, boolean replace) {
        try {
            var preview = onboarding.preview(alias, asId, null);
            println("  Preview for: " + preview.targetId());
            println("  Alias:       " + alias);
            println("  Hostname:    " + preview.sshGResult().hostname()
                + ":" + preview.sshGResult().port());
            println("  User:        " + preview.sshGResult().user());
            if (preview.sshGResult().hasProxyJump()) {
                println("  ProxyJump:   " + preview.sshGResult().proxyJump());
            }
            println("  Profile:     " + preview.profileManifest().displayName()
                + " (" + preview.profileManifest().capabilityProfile() + ")");
            println("  Access:      read-only");
            println("  Key files:   " + preview.sshGResult().identityFileCount());
            println("  SSH Agent:   "
                + (preview.sshGResult().agentEnabled() ? "detected" : "not detected"));

            onboarding.register(alias, preview.targetId(),
                preview.profileManifest().manifestId(), replace);
            println("  Registered:  " + preview.targetId());
            println("  Next: /remote doctor " + preview.targetId());
        } catch (IOException e) {
            println("  Discovery failed: " + e.getMessage());
            log.warn("[remote-cmd] add --from-ssh {} failed: {}", alias, e.getMessage());
        }
    }

    private void cmdAddLegacy(String targetId, String configFile, boolean replace) {
        if (targetId.isEmpty()) {
            println("  Usage: /remote add <targetId> --config <file> [--replace]");
            return;
        }
        Path configPath = Path.of(configFile);
        store.add(targetId, configPath, replace);
        println("  Target registered: " + targetId);
    }

    private void cmdAddInteractive() {
        var discovery = onboarding.discoverTargets();
        if (!discovery.safe()) {
            println("  SSH config contains unsafe directives:");
            for (String reason : discovery.unsafeReasons()) {
                println("    - " + reason);
            }
            println("  Cannot use interactive discovery with unsafe config.");
            println("  Use /remote add <id> --config <file> for explicit endpoint mode.");
            return;
        }
        if (discovery.aliases().isEmpty()) {
            println("  No explicit Host aliases found in SSH config.");
            println("  Use /remote add <id> --config <file> for explicit endpoint mode.");
            return;
        }
        println("  Found " + discovery.aliases().size() + " SSH target(s):");
        int i = 1;
        for (String alias : discovery.aliases()) {
            String source = discovery.sources().getOrDefault(alias, "?");
            println("    " + i + ". " + alias + "  (" + source + ")");
            i++;
        }
        println("  Use /remote add --from-ssh <alias> to register.");
    }

    private void cmdRemove(String targetId) {
        if (targetId.isEmpty()) {
            println("  Usage: /remote remove <targetId>");
            return;
        }
        store.remove(targetId);
        println("  Target removed: " + targetId);
    }

    private void cmdConnect(String targetId) {
        if (targetId.isEmpty()) {
            System.out.println("  用法: 连接 <服务器名称>");
            return;
        }
        if (!store.exists(targetId)) {
            System.out.println("  未找到服务器: " + targetId);
            System.out.println("  可输入 /remote list 查看已登记服务器。");
            return;
        }
        System.out.println("  正在连接 " + targetId + "...");
        try {
            service.connect(targetId);
            System.out.println("  连接成功。");
            System.out.println("  已切换到服务器模式: " + targetId);
            System.out.println("  现在可以输入 [检查服务器] 查看服务状态。");
        } catch (IOException e) {
            System.out.println("  连接失败: 远端程序没有正常启动。");
            System.out.println("  下一步: 输入 /remote doctor " + targetId + " 查看详细原因。");
        }
    }

    private void cmdDisconnect() {
        String active = service.activeTargetId();
        if (active == null) {
            println("  当前没有已连接的服务器。");
            return;
        }
        service.disconnect();
        System.out.println("  已断开与 " + active + " 的连接。");
        System.out.println("  已切换回本地模式。");
    }

    private void cmdDoctor(RemoteCommandParser.ParsedCommand cmd) {
        String targetId = cmd.arg(0);
        boolean verbose = cmd.hasOption("verbose");
        boolean json = cmd.hasOption("json");

        if (targetId.isEmpty()) {
            println("  Usage: /remote doctor <targetId> [--verbose|--json]");
            return;
        }

        var report = doctorService.check(targetId);
        if (json) {
            System.out.println(RemoteDoctorService.renderJson(report));
        } else {
            System.out.print(RemoteDoctorService.renderText(report, verbose));
        }
    }

    private void cmdTools() {
        String active = service.activeTargetId();
        if (active == null) {
            println("  No active connection.");
            return;
        }
        Optional<ToolMount> mount = registry.getMount("remote:" + active);
        if (mount.isEmpty()) {
            println("  No remote tools mounted.");
            return;
        }
        long readOnlyCount = mount.get().toolNames().stream()
            .filter(registry::isReadOnly)
            .count();
        println("  这些工具来自当前服务器的 MCP tools/list：");
        println("  共 " + mount.get().toolNames().size() + " 个，其中只读 " + readOnlyCount
            + " 个；具体权限、描述和参数均来自运行时工具元数据。");
        println("");
        for (String name : mount.get().toolNames()) {
            var tool = registry.lookup(name);
            String readonly = tool.map(t -> t.isReadOnly() ? " [只读]" : " [需确认]").orElse(" [?]");
            String shortName = shortToolName(name);
            String description = tool.map(t -> stripRemoteDescriptionPrefix(t.description()))
                .orElse("MCP 未提供工具描述");
            String inputs = tool.map(t -> summarizeInputSchema(t.inputSchema()))
                .orElse("未知");
            println("  " + shortName + readonly);
            println("    工具名: " + name);
            println("    MCP 描述: " + description);
            println("    输入参数: " + inputs);
            println("");
        }
    }

    static String shortToolName(String name) {
        int separator = name.lastIndexOf("__");
        return separator >= 0 ? name.substring(separator + 2) : name;
    }

    static String stripRemoteDescriptionPrefix(String description) {
        if (description == null || description.isBlank()) return "MCP 未提供工具描述";
        if (description.startsWith("[MCP:remote:")) {
            int end = description.indexOf("] ");
            if (end >= 0 && end + 2 < description.length()) {
                return description.substring(end + 2);
            }
        }
        return description;
    }

    static String summarizeInputSchema(String schema) {
        if (schema == null || schema.isBlank()) return "无";
        try {
            JsonNode root = Tool.MAPPER.readTree(schema);
            JsonNode properties = root.path("properties");
            if (!properties.isObject() || properties.isEmpty()) return "无";

            java.util.Set<String> required = new java.util.HashSet<>();
            JsonNode requiredNode = root.path("required");
            if (requiredNode.isArray()) {
                requiredNode.forEach(node -> required.add(node.asText()));
            }

            java.util.List<String> fields = new java.util.ArrayList<>();
            properties.fieldNames().forEachRemaining(field ->
                fields.add(field + (required.contains(field) ? "（必填）" : "（可选）")));
            return String.join("、", fields);
        } catch (Exception ignored) {
            return "由 MCP Schema 定义";
        }
    }

    private void cmdHelp() {
        println("  /remote list                          List registered targets");
        println("  /remote status                        Show active connection");
        println("  /remote inspect [targetId]            Show target details");
        println("  /remote add --from-ssh <alias>        Register from SSH config");
        println("    [--as <id>] [--profile <id>] [--yes]");
        println("    Profiles: app-down-readonly-v1 (default)");
        println("             postgres-diagnosis-readonly-v1");
        println("  /remote add <id> --config <file>      Register from YAML (legacy)");
        println("  /remote remove <targetId>             Remove target");
        println("  /remote connect <targetId>            Connect to target");
        println("  /remote disconnect                    Disconnect");
        println("  /remote doctor <targetId>             Health check");
        println("    [--verbose] [--json]");
        println("  /remote tools                         List mounted tools");
        println("  /remote help                          This help");
        println("");
        println("  Quick start:");
        println("    1. /remote add --from-ssh <alias>");
        println("    2. /remote doctor <target>");
        println("    3. /remote connect <target>");
        println("    4. /remote tools");
        println("    5. /remote disconnect");
        println("");
        println("  Host key issues:");
        println("    unknown → ssh <alias> once to accept, then re-run doctor");
        println("    changed → verify key through trusted channel first");
        println("  Auth failure → check ssh-add -l and authorized_keys");
        println("  Network failure → verify SSH port and connectivity");
        println("");
        println("  Credentials are used via SSH config/Agent references only.");
        println("  No keys, tokens, or paths are stored in Clawkit target files.");
    }

    private static void println(String text) {
        System.out.println(ConsoleRenderer.GRAY + text + ConsoleRenderer.RESET);
    }
}
