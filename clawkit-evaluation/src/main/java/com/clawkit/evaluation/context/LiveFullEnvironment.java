package com.clawkit.evaluation.context;

import com.clawkit.tools.*;
import com.clawkit.tools.impl.*;
import com.clawkit.tools.action.ActionDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local fixture observer. Its constructor cannot receive a Task or gold; promotion is structural only. */
final class LiveFullEnvironment {
    private final Path workspace;
    private final LiveFullSpec.EnvironmentProgram program;
    private final EvaluationArtifacts artifacts;
    private final String prefix;
    private int revision=1;
    LiveFullEnvironment(Path workspace, LiveFullSpec.AgentInput input, LiveFullSpec.EnvironmentProgram program,
                         EvaluationArtifacts artifacts, String prefix) throws Exception {
        this.workspace=workspace.toAbsolutePath().normalize(); this.program=program; this.artifacts=artifacts; this.prefix=prefix;
        ContinuationRuntime.seed(this.workspace,input.workspaceFiles());
        artifacts.write(prefix+"/environment-initial.json",Map.of("kind",program.kind(),"revision",revision,"source","LOCAL_SYNTHETIC_FIXTURE"));
    }
    ToolRegistry tools() {
        var tools=new ToolRegistry();
        tools.register(new ReadTool(workspace) {
            @Override public Result<String> execute(String args) {
                try {
                    if (EvaluationArtifacts.JSON.readTree(args).path("path").asText().equals("runtime/validation.json"))
                        return new Result.Ok<>(EvaluationArtifacts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(validation()));
                } catch(Exception e) { return new Result.Err<>(new Result.ErrorInfo("T-005","structural validator unavailable")); }
                return super.execute(args);
            }
            @Override public ToolExecutionResult execute(ToolExecutionRequest request) {
                synchronized(LiveFullEnvironment.this) {
                    var result=super.execute(request);
                    if (result.success() && program.statusFile()!=null && request.arguments().path("path").asText().equals(program.statusFile())) {
                        try { artifacts.append(prefix+"/environment-reads.jsonl",Map.of("revision",revision,"runId",runId(request),"path",program.statusFile(),"callId",request.toolCallId(),
                            "observedAt",Instant.now(),"output",result.output(),"statusFileHash",EvaluationArtifacts.sha256(Files.readAllBytes(workspace.resolve(program.statusFile()))))); }
                        catch(Exception e) { throw new IllegalStateException("failed to archive actual fixture read",e); }
                    }
                    return result;
                }
            }
        });
        tools.register(new WriteTool(workspace) {
            private boolean allowed(JsonNode args) {
                if (args==null || !args.path("path").isTextual()) return false;
                try { var path=args.path("path").asText(); ContinuationSpec.relativeFile(path); return path.startsWith("preview/"); }
                catch(Exception e) { return false; }
            }
            @Override public ActionDescriptor describeAction(ToolExecutionRequest request) {
                return allowed(request.arguments()) ? super.describeAction(request) : null;
            }
            @Override public Result<String> execute(String arguments) {
                try { if (!allowed(EvaluationArtifacts.JSON.readTree(arguments))) return new Result.Err<>(new Result.ErrorInfo("T-003","only preview/ writes permitted")); }
                catch(Exception e) { return new Result.Err<>(new Result.ErrorInfo("T-002","invalid arguments")); }
                return super.execute(arguments);
            }
            @Override public ToolExecutionResult execute(ToolExecutionRequest request) {
                var result=super.execute(request);
                if(result.success()) try { observeWrite(request.arguments().path("path").asText(),request.toolCallId(),runId(request)); }
                catch(Exception e) { throw new IllegalStateException("fixture observer failed after a native write; do not repeat blindly",e); }
                return result;
            }
        });
        tools.register(new GlobTool(workspace)); tools.register(new GrepTool(workspace)); return tools;
    }
    private static String runId(ToolExecutionRequest request) {
        return request.scope()==null ? "UNSCOPED_FIXTURE" : request.scope().runId();
    }
    synchronized Map<String,Object> validation() throws Exception {
        var invalid=new ArrayList<String>();
        for(var path:program.previewFiles()) if(!structurallyValid(path,revision)) invalid.add(path);
        return Map.of("kind","STRUCTURE_ONLY_NO_VALUE_ORACLE","currentRevision",revision,"declaredPreviews",program.previewFiles().size(),
            "invalidOrMissing",invalid,"structureValid",invalid.isEmpty());
    }
    synchronized void observeWrite(String path,String callId,String runId) throws Exception {
        if(!program.kind().equals("CHANGING_EVIDENCE") || revision>=3) return;
        boolean promote=revision==1 ? program.previewFiles().contains(path)&&structurallyValid(path,1)
            : program.previewFiles().stream().allMatch(file -> { try { return structurallyValid(file,2); } catch(Exception e) { return false; } });
        if(!promote) return;
        // Archive the exact actual preview bytes triggering the publicly declared structural progression.
        var previewBytes=new TreeMap<String,String>();
        for(var file:program.previewFiles()) if(Files.isRegularFile(workspace.resolve(file))) previewBytes.put(file,Files.readString(workspace.resolve(file)));
        int from=revision; revision++;
        String next=EvaluationArtifacts.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(program.revisions().get(revision-1))+"\n";
        Files.writeString(workspace.resolve(program.statusFile()),next,StandardOpenOption.TRUNCATE_EXISTING);
        artifacts.append(prefix+"/environment-transitions.jsonl",Map.of("from",from,"to",revision,"runId",runId,"triggerCallId",callId,"triggerPath",path,
            "occurredAt",Instant.now(),"previewBytes",previewBytes,"newStatusBytes",next,"source","FIXTURE_OBSERVER_NOT_AGENT_ACTION"));
    }
    boolean structurallyValid(String file,int atRevision) throws Exception {
        var path=workspace.resolve(file); if(!Files.isRegularFile(path)||Files.isSymbolicLink(path))return false;
        JsonNode json; try { json=EvaluationArtifacts.JSON.readTree(path.toFile()); } catch(Exception e){return false;}
        return structurallyValid(program.kind(),file,atRevision,json);
    }
    static boolean structurallyValid(String kind,String file,int atRevision,JsonNode json) {
        if(json==null||!json.isObject())return false;
        if(kind.equals("CONFIG_MIGRATION")) {
            if(!fields(json,"schemaVersion service environment http upstreams retries resources telemetry security rollout storage ownership")
                || !json.path("schemaVersion").isIntegralNumber() || !json.path("service").isTextual() || !json.path("environment").isTextual())return false;
            return fields(json.path("http"),"port workers requestTimeoutSeconds connectTimeoutSeconds maxConnections corsOrigins")
                && json.path("upstreams").isArray() && json.path("upstreams").size()==1
                && fields(json.path("upstreams").get(0),"service url timeoutSeconds") && fields(json.path("retries"),"attempts delaySeconds queueSize")
                && fields(json.path("resources"),"cpuMillicores memoryMiB minReplicas maxReplicas zoneSpread")
                && fields(json.path("telemetry"),"logLevel logFormat redactFields traceSamplePercent metricsPath healthPath alertChannel")
                && fields(json.path("security"),"tlsEnabled tlsMinVersion") && fields(json.path("rollout"),"batchSize rollbackOnFailure drainTimeoutSeconds")
                && fields(json.path("storage"),"enabled retentionDays") && fields(json.path("ownership"),"team");
        }
        String target=file.substring(file.lastIndexOf('/')+1,file.length()-5);
        if(!json.path("target").asText().equals(target) || !json.path("snapshotRevision").isIntegralNumber() || json.path("snapshotRevision").asInt()!=atRevision)return false;
        if(file.startsWith("preview/decisions/")) return fields(json,"target snapshotRevision observedEpoch serviceVersion action repairExecuted eligibility evidenceFile")
            && json.path("observedEpoch").isIntegralNumber() && json.path("serviceVersion").isTextual() && json.path("action").isTextual()
            && json.path("repairExecuted").isBoolean() && json.path("evidenceFile").isTextual()
            && fields(json.path("eligibility"),"maintenance desiredRunning dependencyHealthy authorizedRestart cooldownElapsed")
            && allBooleans(json.path("eligibility"));
        return fields(json,"target snapshotRevision source facts historicalActionsReplayed") && json.path("source").isTextual()
            && json.path("historicalActionsReplayed").isBoolean() && fields(json.path("facts"),"serviceVersion observedEpoch health dependencyHealthy maintenance desiredRunning authorizedRestart lastActionEpoch cooldownSeconds errorCount cpuPercent queueDepth")
            && json.path("facts").path("serviceVersion").isTextual() && json.path("facts").path("observedEpoch").isIntegralNumber();
    }
    private static boolean fields(JsonNode node,String names) {
        if(node==null||!node.isObject())return false; var expected=Set.of(names.split(" ")); var actual=new HashSet<String>();node.fieldNames().forEachRemaining(actual::add);return expected.equals(actual);
    }
    private static boolean allBooleans(JsonNode node) { for(var value:node)if(!value.isBoolean())return false;return true; }
}
