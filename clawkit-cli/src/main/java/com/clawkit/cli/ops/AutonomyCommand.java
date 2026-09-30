package com.clawkit.cli.ops;

import com.clawkit.cli.ApplicationBootstrap;
import com.clawkit.ops.delivery.managed.ManagedOperationsService;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

/** Deterministic product commands; only run creates a narrowly scoped operations Agent. */
@Command(name="autonomy",mixinStandardHelpOptions=true,description={
    "登记和管理隔离 Linux Compose 服务的分层自治闭环。",
    "操作：register, policy, check, run, status, events, approve, reject, command-result, handoff, notifications, pause, resume, stop。",
    "登记默认需审批；policy limited-auto 需要 --confirm-reviewed 和 --review-note。"})
public final class AutonomyCommand implements Callable<Integer> {
    @Spec CommandSpec spec;
    @Parameters(index="0",arity="0..1",description="操作（默认显示帮助）") String operation;
    @Parameters(index="1",arity="0..1",description="已登记应用 ID") String applicationId;
    @Parameters(index="2",arity="0..1",description="权限模式、事件 ID 或命令 ID") String argument;
    @Option(names="--state-dir",description="本地控制数据目录（默认 ~/.clawkit/autonomy）") Path stateDirectory;
    @Option(names="--compose",description="现有隔离 Compose 文件") Path compose;
    @Option(names="--context",description="本地 Docker context") String context;
    @Option(names="--project",description="已创建的 clawkit-autonomy-* 项目") String project;
    @Option(names="--service",description="已存在的无状态服务") String serviceName;
    @Option(names="--dependencies",split=",",description="声明的上游依赖服务，以逗号分隔") List<String> dependencies=List.of();
    @Option(names="--stateless",description="确认目标是可安全启动/重启的无状态服务") boolean stateless;
    @Option(names="--health",description="登记的本地健康检查 URL") URI health;
    @Option(names="--business",description="登记的本地业务检查 URL") URI business;
    @Option(names="--marker",description="业务响应必须包含的标记") String marker;
    @Option(names="--interval",defaultValue="5",description="观察间隔秒数（1..3600）") int interval;
    @Option(names="--actions",split=",",defaultValue="start,restart",description="审阅的动作：start,restart") Set<String> actions;
    @Option(names="--minutes",defaultValue="60",description="权限有效分钟数（1..1440）") int minutes;
    @Option(names="--confirm-reviewed",description="明确确认已审阅该应用、目标及动作范围") boolean reviewed;
    @Option(names="--review-note",description="保存本次人工范围审阅依据（不填密钥）") String reviewNote;
    @Option(names="--confirm",description="明确批准当前具名动作") boolean confirm;
    @Option(names="--once",description="只执行一个观察/决定周期后退出") boolean once;
    @Option(names="--limit",defaultValue="20",description="最近事件条数（1..100）") int limit;
    @Option(names="--details",description="展开原始 Agent 判断和事件细节（默认仅展示标准事实）") boolean details;
    @Option(names="--model",description="运行所用模型") String model;
    @Option(names="--base-url",description="模型 API endpoint") String baseUrl;
    @Option(names="--protocol",description="模型协议") String protocol;
    @Option(names="--notify",description="启用已选择会话的飞书通知；需要 --chat-id 和机器人环境凭据") boolean notify;
    @Option(names="--chat-id",description="明确选择的飞书接收会话 ID（不由 Agent 决定）") String chatId;
    private final ServiceFactory services;
    @FunctionalInterface interface ServiceFactory { ManagedOperationsService create(Path stateDirectory) throws Exception; }
    public AutonomyCommand() { this(ApplicationBootstrap::managedOperations); }
    AutonomyCommand(ServiceFactory services) { this.services=services; }
    @Override public Integer call() throws Exception {
        PrintWriter out=spec.commandLine().getOut();
        if (operation==null) { spec.commandLine().usage(out); return 0; }
        Path root=stateDirectory==null ? Path.of(System.getProperty("user.home"),".clawkit","autonomy") : stateDirectory;
        try {
            var service=services.create(root);
            if (operation.equals("status") && applicationId==null) {
                var ids=service.applications(); if (ids.isEmpty()) out.println("尚未登记应用。使用 autonomy register 登记现有隔离服务。");
                for (String id:ids) render(service.status(id),out,details); out.flush(); return 0;
            }
            require(applicationId,"应用 ID");
            switch(operation) {
                case "register" -> {
                    require(compose,"--compose"); require(project,"--project"); require(serviceName,"--service");
                    require(health,"--health"); require(business,"--business"); require(marker,"--marker");
                    String dockerContext=context==null ? System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "desktop-linux" : "default" : context;
                    render(service.register(new ManagedOperationsService.RegistrationRequest(applicationId,compose,dockerContext,project,serviceName,dependencies,
                        stateless,health,business,marker,Duration.ofSeconds(interval))),out,details);
                    out.println("已登记；默认请求人工审批。登记本身未执行修复。");
                }
                case "policy" -> {
                    require(argument,"权限模式：observe、ask 或 limited-auto");
                    if (argument.equalsIgnoreCase("limited-auto") && (!reviewed || reviewNote==null || reviewNote.isBlank()))
                        throw new IllegalArgumentException("开启自主处理需要 --confirm-reviewed 和 --review-note，记录已审阅的具体范围。");
                    render(service.setPolicy(applicationId,argument,actions,Duration.ofMinutes(minutes),operator(),reviewNote),out,details);
                    out.println("权限已更新；故障或未知结果导致的持久化降级仍保留。");
                }
                case "check" -> { render(service.check(applicationId),out,details); out.println("配置和已登记容器身份检查通过；未调用模型或修复。"); }
                case "status", "handoff" -> render(service.status(applicationId),out,details);
                case "events" -> { for (var event:service.events(applicationId,limit)) render(event,out,details); }
                case "notifications" -> {
                    var view=service.notifications(applicationId);
                    out.println("通知投递：待处理 "+view.pending()+"，已发送 "+view.sent()+"，失败/需核对 "+view.failed());
                    for (String failure:view.failures()) out.println("投递记录："+failure);
                }
                case "pause", "resume", "stop" -> {
                    service.requestMode(applicationId,switch(operation) { case "pause" -> "PAUSED"; case "resume" -> "RUNNING"; default -> "STOPPED"; });
                    out.println(switch(operation) { case "pause" -> "已请求暂停；尚未派发的修复将被取消。";
                        case "resume" -> "已请求恢复；需要仍在运行的控制进程，或再次执行 run。";
                        default -> "已请求停止；已派发的动作将记录实际验证结果。"; });
                }
                case "approve", "reject" -> {
                    require(argument,"待审批事件 ID");
                    if (operation.equals("approve") && !confirm) throw new IllegalArgumentException("批准动作需要 --confirm；先用 status 查看目标和原因。");
                    var command=service.requestCommand(applicationId,argument,operation.equals("approve"),operator());
                    out.println("人工决定已排队："+command.id()+"；动作="+action(command.action())+"，有效至 "+command.expiresAt());
                    out.println("需要该应用的 run 进程消费；可用 command-result 查询，排队不代表已执行。");
                }
                case "command-result" -> { require(argument,"命令 ID"); var result=service.commandResult(applicationId,argument);
                    out.println(!result.completed() ? "人工决定尚未消费。" : result.applied() ? "人工决定已处理；用 status 查看实际修复结果。" : "人工决定被拒绝："+result.detail()); }
                case "run" -> run(service,out);
                default -> throw new IllegalArgumentException("未知操作；使用 autonomy --help 查看可用操作。");
            }
            out.flush(); return 0;
        } catch (Exception e) {
            spec.commandLine().getErr().println("自治操作未完成："+safeError(e)); spec.commandLine().getErr().flush(); return 2;
        }
    }
    private void run(ManagedOperationsService service,PrintWriter out) throws Exception {
        render(service.status(applicationId),out,details); out.flush();
        ManagedOperationsService.NotificationConfiguration notifications=null;
        if (notify) {
            require(chatId,"--chat-id（明确选择通知接收会话）");
            notifications=new ManagedOperationsService.NotificationConfiguration(System.getenv("CLAWKIT_FEISHU_APP_ID"),System.getenv("CLAWKIT_FEISHU_APP_SECRET"),chatId);
        }
        try (var session=ApplicationBootstrap.managedSession(service,applicationId,model,baseUrl,protocol,event -> render(event,out,details),notifications)) {
            Thread shutdown=new Thread(() -> { try { session.close(); } catch (Exception ignored) {} },"clawkit-autonomy-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                if (once) session.once();
                else {
                    session.start(); out.println("持续观察已启动。另一终端可查看 status/events、审批，或 pause/stop。Ctrl+C 停止。"); out.flush();
                    while (!session.stopRequested()) {
                        if (session.failure()!=null) { out.println("控制已暂停："+session.failure()+"；查看状态与证据后再恢复。"); out.flush(); break; }
                        Thread.sleep(500);
                    }
                }
            } finally { try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) {} }
        }
        render(service.status(applicationId),out,details);
    }
    private static void require(Object value,String name) {
        if (value==null || value instanceof String text && text.isBlank()) throw new IllegalArgumentException("缺少 "+name);
    }
    private static String operator() { return System.getProperty("user.name","local-user"); }
    private static String safeError(Exception error) {
        if (error instanceof IllegalArgumentException || error instanceof java.io.IOException)
            return error.getMessage()==null ? error.getClass().getSimpleName() : error.getMessage().replace('\n',' ');
        return error.getClass().getSimpleName()+"；请检查登记配置和本地依赖。";
    }
    public static void render(ManagedOperationsService.StatusView view,PrintWriter out) {
        render(view,out,false);
    }
    private static void render(ManagedOperationsService.StatusView view,PrintWriter out,boolean details) {
        synchronized(out) {
            out.println("应用："+view.applicationId()+"  目标："+view.target());
            out.println("控制进程："+(view.processActive() ? "正在运行" : "未运行"));
            out.println("控制："+switch(view.requestedMode()) { case "RUNNING" -> "请求运行"; case "PAUSED" -> "已暂停"; default -> "已停止"; }
                +"  处置权限："+switch(view.permission()) { case "LIMITED_AUTO" -> "限定自主处理（有效至 "+view.permissionExpiresAt()+"）";
                    case "EXPIRED" -> "权限已过期，请重新配置"; case "OBSERVE" -> "仅观察"; default -> "需人工审批"; });
            out.println("事件状态："+state(view.state()));
            if (view.incidentId()!=null) out.println("事件："+view.incidentId()+"  建议动作："+action(view.action()));
            if (!view.observedFacts().isEmpty()) out.println("最近观察："+facts(view.observedFacts()));
            if (view.lastObservation()!=null) out.println("观察时间："+time(view.lastObservation()));
            if (details && view.reason()!=null) out.println("Agent 判断（需结合事实核对）："+view.reason());
            if (view.degradationReason()!=null) out.println("自动处理已降级，需人工核对。"+(details ? " "+view.degradationReason() : ""));
            if (view.executionOutcome()!=null) out.println("执行结果："+outcome(view.executionOutcome()));
            out.println("证据目录："+view.evidenceDirectory()); out.flush();
        }
    }
    private static void render(ManagedOperationsService.EventView event,PrintWriter out,boolean details) {
        synchronized(out) { out.println(time(event.at())+"  "+eventName(event.kind())+"  "+state(event.state())+"  "+event.incidentId()
            +(details ? "  "+event.detail() : "")); out.flush(); }
    }
    private static String time(Instant value) { return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(value); }
    private static String facts(Map<String,String> values) {
        List<String> facts=new ArrayList<>();
        for (String probe:List.of("SERVICE","HEALTH","BUSINESS","DEPENDENCIES")) {
            if (!values.containsKey(probe)) continue;
            String name=switch(probe) { case "SERVICE" -> "服务"; case "HEALTH" -> "健康检查"; case "BUSINESS" -> "业务检查"; default -> "上游依赖"; };
            facts.add(name+switch(values.get(probe)) { case "RUNNING" -> "运行中"; case "STOPPED" -> "已停止"; case "RESTARTING" -> "正在恢复";
                case "HEALTHY" -> "正常"; case "UNHEALTHY" -> "异常"; default -> "状态未知"; });
        }
        return String.join("；",facts);
    }
    private static String eventName(String kind) { return switch(kind) { case "INCIDENT_CREATED" -> "发现新异常"; case "OBSERVATION_MERGED" -> "归并重复观察";
        case "DECISION_STARTED" -> "开始调查"; case "DECISION_SUBMITTED" -> "已提出下一步"; case "HUMAN_APPROVED" -> "人工已批准";
        case "HUMAN_REJECTED","INTENT_CANCELLED" -> "已取消处理"; case "REPAIR_STARTED" -> "执行前复查"; case "INCIDENT_RECOVERED" -> "独立验证恢复";
        case "DECISION_CANCELLED" -> "调查已取消"; default -> "交接/状态更新"; }; }
    private static String action(String value) { return value==null ? "暂无" : value.equals("start") ? "启动服务" : "重启服务"; }
    private static String state(String value) { return switch(value) { case "NO_INCIDENT" -> "尚未发现异常"; case "OPEN" -> "已发现异常";
        case "INVESTIGATING" -> "正在调查"; case "WAITING" -> "等待复查"; case "AWAITING_APPROVAL" -> "等待人工审批";
        case "EXECUTING" -> "正在处置并验证"; case "RECOVERED" -> "已独立验证恢复"; case "HANDOFF" -> "待人工处理";
        case "CANCELLED" -> "已取消处理"; default -> "状态待核查"; }; }
    private static String outcome(String value) { return switch(value) { case "RECOVERED" -> "已独立验证恢复"; case "OUTCOME_UNKNOWN" -> "结果未知，禁止重复派发";
        case "VERIFICATION_FAILED" -> "恢复验证失败，已转人工"; case "NO_EFFECT" -> "修复未派发"; case "REQUIRES_APPROVAL" -> "需要人工审批";
        default -> "动作被阻止"; }; }
}
