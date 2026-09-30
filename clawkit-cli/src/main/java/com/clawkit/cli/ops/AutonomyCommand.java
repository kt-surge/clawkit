package com.clawkit.cli.ops;

import com.clawkit.cli.ApplicationBootstrap;
import com.clawkit.ops.delivery.managed.ManagedOperationsService;
import com.clawkit.ops.loop.managed.*;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

/** Deterministic product commands; run and diagnose create a narrowly scoped read-only operations Agent. */
@Command(name="autonomy",mixinStandardHelpOptions=true,description={
    "登记和管理隔离 Linux Compose 服务的分层自治闭环。",
    "操作：register, policy, check, diagnose, diagnosis, change-import, metrics, run, status, events, approve, reject, command-result, handoff, notifications, pause, resume, stop。",
    "知识：postmortem, cases, case-review, case-revoke, knowledge-import, knowledge-list, knowledge-search, knowledge-replay, knowledge-review, knowledge-revoke。",
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
    @Option(names="--input",description="人工/CI 变更记录 JSON 文件") Path input;
    @Option(names="--metrics-url",description="已部署的本地 Prometheus 地址，固定查询登记容器的内存历史") URI metricsUrl;
    @Option(names="--query",description="知识检索症状（最多 200 字符）") String query;
    @Option(names="--replay-id",description="本地已保存、通过正反例回放的记录 ID") String replayId;
    @Option(names="--cause",description="人工确认的案例根因类别") DiagnosticReport.Cause confirmedCause;
    @Option(names="--model",description="运行所用模型") String model;
    @Option(names="--base-url",description="模型 API endpoint") String baseUrl;
    @Option(names="--protocol",description="模型协议") String protocol;
    @Option(names="--notify",description="启用已选择会话的飞书通知；需要 --chat-id 和机器人环境凭据") boolean notify;
    @Option(names="--chat-id",description="明确选择的飞书接收会话 ID（不由 Agent 决定）") String chatId;
    private final ServiceFactory services;
    private final DiagnosisFactory diagnosisFactory;
    @FunctionalInterface interface ServiceFactory { ManagedOperationsService create(Path stateDirectory) throws Exception; }
    @FunctionalInterface interface DiagnosisFactory { ManagedOperationsService.DiagnosisView diagnose(ManagedOperationsService service,String id,String model,String baseUrl,String protocol) throws Exception; }
    public AutonomyCommand() { this(ApplicationBootstrap::managedOperations); }
    AutonomyCommand(ServiceFactory services) { this(services,ApplicationBootstrap::managedDiagnosis); }
    AutonomyCommand(ServiceFactory services,DiagnosisFactory diagnosisFactory) { this.services=services; this.diagnosisFactory=diagnosisFactory; }
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
                case "change-import" -> {
                    require(input,"--input"); service.importChange(applicationId,input,operator());
                    out.println("变更记录已导入；后续诊断可引用，时间关联仍需补证。");
                }
                case "metrics" -> {
                    if (!"off".equals(argument)) require(metricsUrl,"--metrics-url（或 metrics <应用> off 关闭）");
                    service.configureMetrics(applicationId,"off".equals(argument) ? null : metricsUrl);
                    out.println("指标来源配置已更新；缺失/过期/warning 结果会显式保留。");
                }
                case "diagnose" -> {
                    var view=diagnosisFactory.diagnose(service,applicationId,model,baseUrl,protocol);
                    renderDiagnosis(view,out,details); if (view.failureType()!=null) return 2;
                }
                case "diagnosis" -> renderDiagnosis(service.diagnosis(applicationId),out,details);
                case "knowledge-import" -> {
                    require(input,"--input（流程版本 JSON）"); var ref=service.importKnowledge(applicationId,input);
                    out.println("已保存知识草稿："+ref.id()+"@"+ref.version()+"；需正反例回放和人工审阅，不增加动作权限。");
                }
                case "knowledge-list" -> {
                    for(var entry:service.knowledge(applicationId).runbooks()) out.println(entry.runbook().id()+"@"+entry.runbook().version()+"  "+entry.state()+"  "+entry.runbook().title());
                }
                case "knowledge-search" -> {
                    require(query,"--query"); var result=service.searchKnowledge(applicationId,query);
                    for(var m:result.runbooks()) out.println(m.reference().id()+"@"+m.reference().version()+"  "+m.title()+"  当前条件="+(m.applicable() ? "符合" : "不符合/缺证")+"  "+m.matchReason());
                    for(var c:result.cases()) out.println("案例："+c.id()+"  人工确认原因="+c.confirmedCause()+"  结果="+c.outcome());
                    if(result.runbooks().isEmpty() && result.cases().isEmpty()) out.println("没有符合环境、服务、版本及审阅状态的命中。");
                    out.println("检索是处置参考，当前事实仍需采证；不适用流程不可触发，历史成功不增加授权。");
                }
                case "knowledge-replay" -> {
                    require(input,"--input（回放样本 JSON）"); var store=service.knowledge(applicationId);
                    var report=store.replay(knowledgeReference(store),ManagedKnowledgeStore.read(input,ManagedKnowledgeStore.ReplayInput.class,262144));
                    out.println("回放："+report.id()+"  通过="+report.results().stream().filter(OpsKnowledge.ReplayResult::passed).count()+"/"+report.results().size()+"  可审阅="+report.qualified());
                    out.println("回放只检查固定事实与适用条件，不执行动作，不代表模型准确率。");
                    if(!report.qualified()) return 2;
                }
                case "knowledge-review", "knowledge-revoke" -> {
                    require(reviewNote,"--review-note"); var store=service.knowledge(applicationId); var ref=knowledgeReference(store);
                    if(operation.equals("knowledge-review")) {
                        if(!reviewed) throw new IllegalArgumentException("审阅知识需要 --confirm-reviewed；知识资格与动作授权分别记录。");
                        require(replayId,"--replay-id"); store.review(ref,replayId,operator(),reviewNote);
                        out.println("知识版本已审阅；未改变应用权限。");
                    } else { store.revoke(ref,operator(),reviewNote); out.println("知识版本已撤销；保留版本与审阅记录，待执行建议会重新检查。"); }
                }
                case "postmortem" -> { var draft=service.postmortem(applicationId); out.println("复盘草稿："+draft.id()+"  结果="+draft.outcome()+"；根因待人工确认。"); }
                case "cases" -> {
                    for(var c:service.knowledge(applicationId).cases()) out.println(c.opsCase().id()+"  "+c.state()+"  "+c.opsCase().outcome()
                        +"  原因="+(c.review()==null || c.review().confirmedCause()==null ? "待确认" : c.review().confirmedCause()));
                }
                case "case-review", "case-revoke" -> {
                    require(argument,"案例 ID"); require(reviewNote,"--review-note");
                    if(operation.equals("case-review") && (!reviewed || confirmedCause==null)) throw new IllegalArgumentException("案例审阅需要 --confirm-reviewed 和 --cause，不能直接采用模型根因。");
                    service.knowledge(applicationId).reviewCase(argument,confirmedCause,operation.equals("case-revoke"),operator(),reviewNote);
                    out.println(operation.equals("case-revoke") ? "案例已撤销；引用该案例的流程不再生效。" : "案例人工审阅已记录；未增加动作权限。");
                }
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
    private OpsKnowledge.Reference knowledgeReference(ManagedKnowledgeStore store) throws Exception {
        require(argument,"知识 ID@版本"); String[] parts=argument.split("@",-1);
        if(parts.length!=2) throw new IllegalArgumentException("知识版本格式为 ID@版本");
        return store.reference(parts[0],Integer.parseInt(parts[1]));
    }
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
    public static void renderDiagnosis(ManagedOperationsService.DiagnosisView view,PrintWriter out,boolean details) {
        out.println("应用："+view.applicationId()+"  诊断时间："+(view.at()==null ? "尚无" : time(view.at())));
        out.println("调查结论："+view.summary());
        for (var h:view.hypotheses()) {
            String cause=switch(h.cause()) { case "DEPENDENCY_FAILURE" -> "上游依赖故障"; case "CONFIGURATION_MISMATCH" -> "配置不匹配";
                case "RESOURCE_EXHAUSTION" -> "资源压力"; case "APPLICATION_FAILURE" -> "应用自身故障";
                case "SELF_RECOVERY" -> "已自行恢复"; default -> "原因待确认"; };
            String assessment=switch(h.assessment()) { case "SUPPORTED" -> "有证据支持的推断"; case "SUSPECTED" -> "待补证假设"; default -> "未知"; };
            out.println(cause+"（"+assessment+"）："+h.explanation());
            if (!h.supportRefs().isEmpty()) out.println("  支持证据："+String.join(", ",h.supportRefs()));
            if (!h.counterRefs().isEmpty()) out.println("  反证："+String.join(", ",h.counterRefs()));
            if (!h.missingEvidence().isEmpty()) out.println("  待确认："+String.join("；",h.missingEvidence()));
            if (!h.alternatives().isEmpty()) out.println("  替代解释："+String.join(", ",h.alternatives()));
        }
        if (view.failureType()!=null) out.println("调查已交接人工："+view.failureType());
        if (view.decision()!=null) out.println("建议："+switch(view.decision()) { case "WAIT" -> "等待复查"; case "INVESTIGATE" -> "继续补证";
            case "PROPOSE_ACTION" -> action(view.action())+"，仍需现有授权与现场复查"; default -> "交接人工处理"; });
        if (details) for (var evidence:view.evidence()) out.println("["+evidence.id()+"] "+evidence.probe()+" "+time(evidence.at())+" "+evidence.quality()
            +" "+evidence.summary()+"（"+evidence.limitation()+"）");
        out.println("诊断记录："+view.artifact()); out.flush();
    }
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
