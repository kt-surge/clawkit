package com.clawkit.cli;

import com.clawkit.engine.SessionService;
import com.clawkit.engine.ThinkingMode;
import com.clawkit.engine.impl.AgentEngine;
import com.clawkit.memory.impl.DiskMemoryService;
import com.clawkit.observability.FileRunRecorder;
import com.clawkit.observability.RunReader;
import com.clawkit.provider.LLMConfig;
import com.clawkit.provider.LLMProvider;
import com.clawkit.provider.ProviderFactory;
import com.clawkit.tools.Tool;
import com.clawkit.tools.ToolRegistry;
import com.clawkit.context.SkillLoader;
import com.clawkit.tools.impl.*;
import com.clawkit.tools.mcp.*;
import com.clawkit.cli.remote.FileRemoteTargetStore;
import com.clawkit.cli.remote.RemoteConnectionService;
import com.clawkit.cli.remote.RemoteTargetStore;
import com.clawkit.ops.delivery.OpsInvestigationFacade;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * 应用装配器。将 ClawkitApp 的 picocli 参数 + 配置组装为 ApplicationContext。
 */
public class ApplicationBootstrap {
    public static com.clawkit.ops.delivery.managed.ManagedOperationsService managedOperations(Path stateRoot) throws java.io.IOException {
        return new com.clawkit.ops.delivery.managed.ManagedOperationsService(stateRoot,new com.clawkit.ops.mcp.ProcessCommandExecutor(),
            java.time.Clock.systemUTC(),new com.clawkit.cli.remote.ManagedRemoteSources(
                Path.of(System.getProperty("user.home"),".clawkit","remote-targets.yaml")));
    }
    public static com.clawkit.ops.delivery.managed.ManagedOperationsService.RunningSession managedSession(
            com.clawkit.ops.delivery.managed.ManagedOperationsService service,String id,String model,String baseUrl,String protocol,
            java.util.function.Consumer<com.clawkit.ops.delivery.managed.ManagedOperationsService.EventView> events) throws Exception {
        return managedSession(service,id,model,baseUrl,protocol,events,null);
    }
    public static com.clawkit.ops.delivery.managed.ManagedOperationsService.RunningSession managedSession(
            com.clawkit.ops.delivery.managed.ManagedOperationsService service,String id,String model,String baseUrl,String protocol,
            java.util.function.Consumer<com.clawkit.ops.delivery.managed.ManagedOperationsService.EventView> events,
            com.clawkit.ops.delivery.managed.ManagedOperationsService.NotificationConfiguration notifications) throws Exception {
        return service.open(id,managedProvider(model,baseUrl,protocol),events,notifications);
    }
    public static com.clawkit.ops.delivery.managed.ManagedOperationsService.DiagnosisView managedDiagnosis(
            com.clawkit.ops.delivery.managed.ManagedOperationsService service,String id,String model,String baseUrl,String protocol) throws Exception {
        return service.diagnose(id,managedProvider(model,baseUrl,protocol));
    }
    private static LLMProvider managedProvider(String model,String baseUrl,String protocol) {
        var resolved=ConfigResolver.resolve(model,baseUrl,protocol,false,null,System.getenv(),Path.of(System.getProperty("user.home")));
        if (resolved.apiKey()==null || resolved.apiKey().isBlank())
            throw new ConfigurationException("C-003","CLAWKIT_API_KEY is not set","autonomy control was not started","Set CLAWKIT_API_KEY before run; status and check do not need a key.");
        var effective=resolved.effective();
        var config=LLMConfig.builder().apiKey(resolved.apiKey()).baseUrl(effective.baseUrl()).model(effective.model())
            .protocol(LLMConfig.Protocol.valueOf(effective.protocol().toUpperCase(java.util.Locale.ROOT)))
            .requestTimeout(Duration.ofSeconds(Math.min(45,effective.requestTimeoutSeconds()))).maxRetries(0).build();
        return ProviderFactory.create(config);
    }

    /**
     * 完整装配流程，返回包含所有依赖的 ApplicationContext。
     */
    public static ApplicationContext bootstrap(
            String model, String baseUrl, String protocol, boolean thinking, Path rootDir) {

        ResolvedConfiguration resolved = ConfigResolver.resolve(model, baseUrl, protocol,
            thinking, rootDir, System.getenv(), Path.of(System.getProperty("user.home")));
        return bootstrap(resolved);
    }

    static ApplicationContext bootstrap(ResolvedConfiguration resolved) {
        EffectiveConfig effective = resolved.effective();

        Path workDir = ApplicationBootstrap.resolveWorkDir(effective.rootDir());

        // API Key
        String apiKey = resolved.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new ConfigurationException("C-003", "CLAWKIT_API_KEY is not set",
                "clawkit was not started",
                "In PowerShell run: $env:CLAWKIT_API_KEY = '<your-deepseek-key>'");
        }

        // Protocol
        LLMConfig.Protocol protocolEnum;
        try {
            protocolEnum = LLMConfig.Protocol.valueOf(effective.protocol().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("C-004", "Unsupported protocol: " + effective.protocol(),
                "the provider was not created", "Use OPENAI_COMPAT for DeepSeek.");
        }

        // Provider
        LLMConfig config = LLMConfig.builder()
            .apiKey(apiKey).baseUrl(effective.baseUrl()).model(effective.model())
            .protocol(protocolEnum)
            .requestTimeout(Duration.ofSeconds(effective.requestTimeoutSeconds()))
            .maxRetries(effective.maxRetries())
            .build();
        LLMProvider provider = ProviderFactory.create(config);

        // Tool registry
        Path userSkillsDir = Path.of(System.getProperty("user.home"), ".agents", "skills");
        ToolRegistry registry = createToolRegistry(workDir, userSkillsDir);

        // MCP
        McpConfig mcpConfig = McpConfig.load(workDir);
        if (!mcpConfig.servers().isEmpty()) {
            var servers = new LinkedHashMap<>(mcpConfig.servers());
            boolean hasInteractive = servers.values().stream().anyMatch(s -> !s.disabled());
            if (hasInteractive) {
                System.out.println();
                for (var entry : servers.entrySet()) {
                    var sc = entry.getValue();
                    if (sc.disabled()) continue;
                    System.out.print("  Enable MCP [" + sc.name() + "] ("
                        + sc.transport().name().toLowerCase() + ")? [Y/n] ");
                    if (System.console() == null) {
                        throw new ConfigurationException("C-007",
                            "Interactive terminal required to approve MCP server '" + sc.name() + "'",
                            "MCP servers were not started",
                            "Run from a terminal or use docker run -it; otherwise disable the server.");
                    }
                    String line = System.console().readLine();
                    String answer = line == null ? "n" : line.trim().toLowerCase();
                    if ("n".equals(answer) || "no".equals(answer)) {
                        servers.put(entry.getKey(),
                            new McpServerConfig(sc.name(), sc.command(), sc.args(),
                                sc.url(), sc.env(), true));
                        System.out.println(ClawkitApp.GRAY + "    " + sc.name() + " disabled." + ClawkitApp.RESET);
                    }
                }
                System.out.println();
            }
            mcpConfig = new McpConfig(servers);
        }
        McpManager mcpManager = new McpManager();
        List<Tool> mcpTools = mcpManager.startAll(mcpConfig, workDir);
        for (Tool t : mcpTools) registry.register(t);

        // Memory
        ThinkingMode mode = effective.thinking() ? ThinkingMode.TWO_STAGE : ThinkingMode.OFF;
        Path memoryDir = Path.of(System.getProperty("user.home"), ".clawkit", "memory");
        DiskMemoryService memoryService = new DiskMemoryService(memoryDir);
        String memoryIndex = memoryService.loadIndex();

        // Engine — PR-2: AgentRuntimeDependencies 构造
        String workspaceRules = ClawkitApp.loadWorkspaceRules(workDir);

        // Runtime integrations are created before the engine so production does not
        // rely on post-construction setters.
        Path projectSkillsDir = workDir.resolve(".clawkit").resolve("skills");
        SkillLoader skillLoader = new SkillLoader(userSkillsDir, projectSkillsDir);
        var skillRuntime = new com.clawkit.engine.impl.DefaultSkillRuntime(skillLoader);

        // PR-9: 统一 recorder — FileRunRecorder 加入共享 CompositeRunRecorder
        Path clawkitDir = Path.of(System.getProperty("user.home"), ".clawkit");
        var sharedRecorder = new com.clawkit.observability.CompositeRunRecorder(
            new com.clawkit.observability.FileRunRecorder(clawkitDir));
        var gateway = new com.clawkit.engine.impl.ObservingProviderGateway(provider, sharedRecorder);
        var memoryHooks = new com.clawkit.engine.impl.DefaultMemoryHooks(
            memoryService, gateway, provider.getContextWindow(), provider.getEncoding());
        var deps = new com.clawkit.engine.AgentRuntimeDependencies(
            gateway, null, registry,
            provider.getContextWindow(), provider.getEncoding(),
            sharedRecorder, memoryHooks, skillRuntime);
        AgentEngine engine = new AgentEngine(deps, workDir.toString(), mode, memoryIndex);
        engine.setWorkspaceRules(workspaceRules);
        // contextPipeline 已由构造器自建，无需手动 init

        // P1-G5：进程启动恢复扫描——崩溃遗留的副作用 Attempt 按结果未知 reconcile
        try {
            var recovery = engine.recoverPendingAttempts();
            if (recovery.scanned() > 0) {
                org.slf4j.LoggerFactory.getLogger(ApplicationBootstrap.class)
                    .info("[Bootstrap] reliability recovery: {}", recovery);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ApplicationBootstrap.class)
                .warn("[Bootstrap] reliability recovery failed: {}", e.getMessage());
        }

        // Skills are owned by SkillRuntime; only its catalog is refreshed here.
        engine.rebuildSkillCatalog();

        // Observability
        RunReader runReader = new RunReader(clawkitDir);

        // Sessions (PR-12: SessionStore 接口)
        var sessionStore = new com.clawkit.engine.impl.FileSessionStore(
            clawkitDir.resolve("sessions"));
        SessionService sessionService = new SessionService(sessionStore);
        sessionService.setProviderGateway(gateway);  // Session 摘要经 gateway
        engine.setSessionService(sessionService);

        // REMOTE-0: Remote target store and connection service
        Path remoteStorePath = clawkitDir.resolve("remote-targets.yaml");
        RemoteTargetStore remoteTargetStore = new FileRemoteTargetStore(remoteStorePath);
        RemoteConnectionService remoteService = new RemoteConnectionService(remoteTargetStore, registry);

        // OPS-PRODUCT-LOOP-1: Investigation facade with session factories
        // Borrow: reuse PRODUCT-1 connected session (never close)
        OpsInvestigationFacade.InitialReadSessionProvider borrowProvider = targetId -> {
            var session = remoteService.getSession();
            if (session != null && session.isReady()
                && targetId.equals(remoteService.activeTargetId())) {
                return new com.clawkit.ops.delivery.RemoteMcpSessionAdapter(session);
            }
            throw new java.io.IOException(
                "目标 " + targetId + " 未连接。请先使用 /remote connect " + targetId);
        };

        // Fresh: open a new SSH/MCP session using the stored target config
        OpsInvestigationFacade.FreshReadSessionFactory freshFactory = targetId -> {
            var reg = remoteTargetStore.getRegistration(targetId);
            if (reg.isEmpty()) {
                var targetConfig = remoteTargetStore.get(targetId)
                    .orElseThrow(() -> new java.io.IOException("target not found: " + targetId));
                var descriptor = com.clawkit.cli.remote.RemoteTargetResolver
                    .resolveLegacyDescriptor(targetConfig);
                var spec = com.clawkit.cli.remote.RemoteTargetResolver
                    .resolveLegacyConnectionSpec(targetConfig);
                var session = new com.clawkit.tools.remote.RemoteMcpSession(descriptor, spec);
                session.start();
                return new com.clawkit.ops.delivery.RemoteMcpSessionAdapter(session);
            }
            var descriptor = com.clawkit.cli.remote.RemoteTargetResolver
                .resolveDescriptor(reg.get());
            var spec = com.clawkit.cli.remote.RemoteTargetResolver
                .resolveConnectionSpec(reg.get());
            var session = new com.clawkit.tools.remote.RemoteMcpSession(descriptor, spec);
            session.start();
            return new com.clawkit.ops.delivery.RemoteMcpSessionAdapter(session);
        };

        // A2 current release is deliberately remote-read-only.  Do not infer write
        // authority from CLAWKIT_REMOTE_FIX_* being present: that would silently
        // turn a user's diagnostic shell into a remote repair client.  Fixture
        // approval tests provide their own explicit in-memory FixSessionFactory.
        OpsInvestigationFacade.FixSessionFactory fixFactory = null;

        OpsInvestigationFacade opsFacade = new OpsInvestigationFacade(
            clawkitDir, borrowProvider, freshFactory, fixFactory, ProviderFactory::create);

        return new ApplicationContext(
            engine, provider, registry, sessionService, skillLoader,
            mcpManager, memoryService, runReader,
            null, // reader — 由 ClawkitApp 创建（需要 Terminal 初始化）
            new java.util.ArrayList<>(), // imChannels
            workDir, effective.model(), mode, effective,
            remoteService, remoteTargetStore, opsFacade, gateway);
    }

    // ── 静态工具方法 ─────────────────────────────────────────────────

    /** 创建 ToolRegistry 并注册 8 个内置工具 + 安全拦截器 */
    static ToolRegistry createToolRegistry(Path workDir, Path... extraReadRoots) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool(workDir, extraReadRoots));
        registry.register(new WriteTool(workDir));
        registry.register(new TodoWriteTool());
        registry.register(new WebFetchTool());
        registry.register(new BashTool(workDir));
        registry.register(new GitReadTool(workDir));
        registry.register(new EditTool(workDir));
        registry.register(new GlobTool(workDir));
        registry.register(new GrepTool(workDir));
        registry.addInterceptor(new CommandSafetyInterceptor());
        return registry;
    }

    static Path resolveWorkDir(Path rootDir) {
        if (rootDir == null) {
            return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        }
        Path path = rootDir.toAbsolutePath().normalize();
        try {
            if (!java.nio.file.Files.exists(path)) {
                java.nio.file.Files.createDirectories(path);
            }
        } catch (java.io.IOException e) {
            throw new ConfigurationException("C-001", "Cannot create root directory: " + path,
                "clawkit was not started", "Choose an existing writable directory with --root.");
        }
        return path;
    }
}
