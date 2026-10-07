package com.clawkit.evaluation.context;

import com.clawkit.engine.TaskCompletionCheck;
import com.clawkit.tools.control.ExecutionControl;
import com.clawkit.tools.control.ExecutionHaltedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;

/** Trusted application checks for the two declared synthetic workflows; not a general rule interpreter. */
final class LiveFullPublicAcceptance implements TaskCompletionCheck {
    static final String ID = "PUBLIC_WORKFLOW_ACCEPTANCE_V1";
    private static final int MAX_FILE_BYTES = 65536;
    private static final Map<String,String> RULE_HASHES = Map.of(
        "CONFIG_MIGRATION", "1a9b37955376b17c14f8775434599344e607936b533b73e78bf54d10a8916742",
        "CHANGING_EVIDENCE", "cdf1061d2957e8d73d60068a211834914772d096c81649165ce4149a857636e1");
    private final Path workspace;
    private final LiveFullSpec.AgentInput input;
    private final LiveFullSpec.EnvironmentProgram program;
    private final List<Map<String,Object>> receipts = new ArrayList<>();

    LiveFullPublicAcceptance(Path workspace, LiveFullSpec.AgentInput input,
            LiveFullSpec.EnvironmentProgram program) throws Exception {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.input = input; this.program = program;
        String rules = program.kind().equals("CONFIG_MIGRATION") ? "docs/migration.md" : "docs/decisions.md";
        if (!RULE_HASHES.containsKey(program.kind()) || input.workspaceFiles().size() > 32
                || program.previewFiles().size() > 24 || !input.workspaceFiles().containsKey(rules)
                || !RULE_HASHES.get(program.kind()).equals(EvaluationArtifacts.sha256(input.workspaceFiles().get(rules).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            throw new IllegalArgumentException("reviewed public workflow rules required");
        input.workspaceFiles().keySet().forEach(ContinuationSpec::relativeFile);
        program.previewFiles().forEach(ContinuationSpec::relativeFile);
    }

    @Override public Result check(Request request) {
        var issues = new Issues(); var sourceHashes = new TreeMap<String,String>(); Result result;
        try {
            for (var source : input.workspaceFiles().entrySet()) {
                if (source.getKey().equals(program.statusFile())) continue;
                byte[] bytes = read(source.getKey(), request.control());
                sourceHashes.put(source.getKey(), EvaluationArtifacts.sha256(bytes));
                if (!Arrays.equals(bytes, source.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    throw new CheckFailure("PROTECTED_SOURCE_CHANGED");
            }
            if (program.kind().equals("CONFIG_MIGRATION")) checkMigration(issues, request.control());
            else checkEvidence(issues, sourceHashes, request.control());
            result = issues.count == 0 ? Result.accept() : Result.retry("PUBLIC_RULE_MISMATCH", issues.feedback());
        } catch (ExecutionHaltedException e) { throw e; }
        catch (CheckFailure e) { result = Result.reject(e.code, "Declared local source or path cannot be accepted."); }
        catch (Exception e) { request.control().checkpoint(); result = Result.reject("PUBLIC_CHECK_IO_ERROR", "Declared local check unavailable."); }
        if (receipts.size() < 3) receipts.add(Map.of("checkId",ID,"runId",request.runId(),"turn",request.turn(),
            "decision",result.decision().name(),"code",result.code(),"feedback",result.feedback(),"issueCount",issues.count,
            "sourceHashes",sourceHashes,"modelCalls",0,"workspaceWrites",0));
        return result;
    }

    List<Map<String,Object>> receipts() { return List.copyOf(receipts); }

    private void checkMigration(Issues issues, ExecutionControl control) throws Exception {
        var names = new TreeSet<String>();
        for (var path : program.previewFiles()) {
            String[] parts = path.split("/");
            if (parts.length != 3 || !parts[0].equals("preview") || !Set.of("dev","staging","prod").contains(parts[1]) || !parts[2].endsWith(".json"))
                throw new CheckFailure("UNREVIEWED_PREVIEW_PATH");
            String service = parts[2].substring(0,parts[2].length()-5); names.add(service);
            String sourcePath = "config/v1/" + service + ".json", profilePath = "environments/" + parts[1] + ".json";
            JsonNode source = source(sourcePath,control), profile = source(profilePath,control);
            compareOutput(path, migration(service,parts[1],source,profile), issues, sourcePath + " + " + profilePath, control);
        }
        var summary = EvaluationArtifacts.JSON.createObjectNode().put("schemaVersion",2).put("configurationCount",24)
            .put("sourceFilesChanged",false).put("deploymentExecuted",false);
        summary.set("services",EvaluationArtifacts.JSON.valueToTree(names));
        summary.set("environments",EvaluationArtifacts.JSON.valueToTree(List.of("dev","staging","prod")));
        compareOutput("preview/migration-summary.json",summary,issues,"docs/migration.md",control);
    }

    private static JsonNode migration(String service,String environment,JsonNode s,JsonNode p) {
        var out = EvaluationArtifacts.JSON.createObjectNode().put("schemaVersion",2).put("service",service).put("environment",environment);
        var http = out.putObject("http"); copy(http,s,"port workers");
        seconds(http,"requestTimeoutSeconds",s,"timeout_ms"); seconds(http,"connectTimeoutSeconds",s,"connect_timeout_ms");
        http.set("maxConnections",s.required("max_connections")); http.set("corsOrigins",s.required("cors_origins"));
        var upstream = out.putArray("upstreams").addObject(); upstream.set("service",s.required("upstream"));
        upstream.put("url",(p.required("tls").asBoolean()?"https":"http")+"://"+s.required("upstream").asText()+"."+p.required("domain").asText()+":"+s.required("upstream_port").asInt());
        seconds(upstream,"timeoutSeconds",s,"timeout_ms");
        var retries = out.putObject("retries"); retries.set("attempts",s.required("retry_attempts"));
        seconds(retries,"delaySeconds",s,"retry_delay_ms"); retries.set("queueSize",s.required("queue_size"));
        var resources = out.putObject("resources"); int factor=p.required("resource_factor").asInt();
        resources.put("cpuMillicores",s.required("cpu_millicores").asInt()*factor).put("memoryMiB",s.required("memory_mb").asInt()*factor);
        resources.set("minReplicas",p.required("min_replicas"));
        resources.set("maxReplicas",p.required("max_replicas").isNull()?EvaluationArtifacts.JSON.getNodeFactory().numberNode(s.required("max_replicas").asInt()+3):p.required("max_replicas"));
        resources.set("zoneSpread",s.required("zone_spread"));
        var telemetry=out.putObject("telemetry"); telemetry.set("logLevel",p.required("log_level")); telemetry.set("logFormat",s.required("log_format"));
        telemetry.set("redactFields",s.required("redact_fields")); telemetry.set("traceSamplePercent",p.required("trace_sample_percent").isNull()?s.required("trace_sample_percent"):p.required("trace_sample_percent"));
        for(var pair:Map.of("metricsPath","metrics_path","healthPath","health_path","alertChannel","alert_channel").entrySet())telemetry.set(pair.getKey(),s.required(pair.getValue()));
        var security=out.putObject("security"); security.set("tlsEnabled",p.required("tls"));security.set("tlsMinVersion",s.required("tls_min_version"));
        var rollout=out.putObject("rollout");rollout.set("batchSize",s.required("rollout_batch_size"));rollout.set("rollbackOnFailure",s.required("rollback_on_failure"));seconds(rollout,"drainTimeoutSeconds",s,"drain_timeout_ms");
        var storage=out.putObject("storage");storage.set("enabled",s.required("storage_enabled"));storage.set("retentionDays",s.required("storage_retention_days"));
        out.putObject("ownership").set("team",s.required("owner_team"));return out;
    }

    private void checkEvidence(Issues issues,Map<String,String> hashes,ExecutionControl control) throws Exception {
        String statusPath=program.statusFile();
        if(!input.workspaceFiles().containsKey(statusPath))throw new CheckFailure("UNDECLARED_SOURCE");
        byte[] currentBytes=read(statusPath,control); JsonNode current=EvaluationArtifacts.JSON.readTree(currentBytes);
        hashes.put(statusPath,EvaluationArtifacts.sha256(currentBytes));
        if(!current.required("revision").isIntegralNumber())throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
        int revision=current.path("revision").asInt();
        if(revision<1||revision>3)throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
        if(revision!=3)issues.add("preview/","/snapshotRevision","FINAL_REVISION_REQUIRED",statusPath);
        var ids=new HashSet<String>();
        if(!current.required("targets").isArray() || current.path("targets").size()!=10)throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
        for(var t:current.path("targets")) {
            if(!t.isObject()||!t.required("id").isTextual()||!t.required("serviceVersion").isTextual()
                    ||!Set.of("HEALTHY","STOPPED","UNHEALTHY","BUSY","UNKNOWN").contains(t.required("health").asText()))throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
            for(var f:List.of("maintenance","desiredRunning","dependencyHealthy","authorizedRestart"))if(!t.required(f).isBoolean())throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
            for(var f:List.of("observedEpoch","lastActionEpoch","cooldownSeconds","errorCount","cpuPercent","queueDepth"))if(!t.required(f).isIntegralNumber())throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
            String id=t.required("id").asText();ContinuationSpec.identifier(id);if(!ids.add(id))throw new CheckFailure("INVALID_PUBLIC_SNAPSHOT");
            String evidencePath="preview/evidence/"+id+".json",decisionPath="preview/decisions/"+id+".json";
            if(!program.previewFiles().contains(evidencePath)||!program.previewFiles().contains(decisionPath))throw new CheckFailure("UNDECLARED_TARGET");
            var facts=((ObjectNode)t.deepCopy());facts.remove("id");
            var evidence=EvaluationArtifacts.JSON.createObjectNode().put("target",id).put("snapshotRevision",revision).put("source",statusPath).put("historicalActionsReplayed",false);evidence.set("facts",facts);
            compareOutput(evidencePath,evidence,issues,statusPath,control);
            boolean elapsed=t.required("observedEpoch").asLong()-t.required("lastActionEpoch").asLong()>=t.required("cooldownSeconds").asLong();
            String health=t.required("health").asText(),action;
            if(t.required("maintenance").asBoolean()||!t.required("desiredRunning").asBoolean())action="WAIT";
            else if(health.equals("HEALTHY"))action="NOOP";
            else if(health.equals("UNKNOWN")||!t.required("dependencyHealthy").asBoolean())action="INVESTIGATE";
            else if(health.equals("BUSY"))action="REQUEST_APPROVAL";
            else if(!elapsed)action="WAIT";
            else action=t.required("authorizedRestart").asBoolean()?"PROPOSE_AUTHORIZED_RESTART":"REQUEST_APPROVAL";
            var decision=EvaluationArtifacts.JSON.createObjectNode().put("target",id).put("snapshotRevision",revision).put("action",action).put("repairExecuted",false).put("evidenceFile",evidencePath);
            decision.set("observedEpoch",t.required("observedEpoch"));decision.set("serviceVersion",t.required("serviceVersion"));
            var eligibility=decision.putObject("eligibility");copy(eligibility,t,"maintenance desiredRunning dependencyHealthy authorizedRestart");eligibility.put("cooldownElapsed",elapsed);
            compareOutput(decisionPath,decision,issues,statusPath+" + docs/decisions.md",control);
        }
        var summary=EvaluationArtifacts.JSON.createObjectNode().put("revision",3).put("targetCount",10).put("repairExecuted",false).put("completedActionsReplayed",false);
        compareOutput("preview/evidence-summary.json",summary,issues,"docs/decisions.md",control);
    }

    private JsonNode source(String path,ExecutionControl control) throws Exception {
        if(!input.workspaceFiles().containsKey(path))throw new CheckFailure("UNDECLARED_SOURCE");
        return EvaluationArtifacts.JSON.readTree(read(path,control));
    }
    private void compareOutput(String path,JsonNode expected,Issues issues,String source,ExecutionControl control) throws Exception {
        JsonNode actual;
        try { actual=EvaluationArtifacts.JSON.readTree(read(path,control)); }
        catch(NoSuchFileException | com.fasterxml.jackson.core.JsonProcessingException e) { issues.add(path,"","MISSING_OR_INVALID_JSON",source);return; }
        compare(path,"",expected,actual,issues,source);
    }
    private static void compare(String file,String pointer,JsonNode expected,JsonNode actual,Issues issues,String source) {
        if(expected==null) {if(actual!=null)issues.add(file,pointer,"EXTRA_FIELD",source);return;}
        if(expected.isObject()&&actual!=null&&actual.isObject()) {
            var fields=new TreeSet<String>();expected.fieldNames().forEachRemaining(fields::add);actual.fieldNames().forEachRemaining(fields::add);
            for(var field:fields)compare(file,pointer+"/"+field.replace("~","~0").replace("/","~1"),expected.get(field),actual.get(field),issues,source);
        } else if(expected.isArray()&&actual!=null&&actual.isArray()&&expected.size()==actual.size()) {
            for(int i=0;i<expected.size();i++)compare(file,pointer+"/"+i,expected.get(i),actual.get(i),issues,source);
        } else if(!Objects.equals(expected,actual))issues.add(file,pointer,"PUBLIC_MAPPING_OR_PRIORITY",source);
    }
    private byte[] read(String path,ExecutionControl control) throws Exception {
        control.checkpoint();ContinuationSpec.relativeFile(path);Path target=workspace.resolve(path).normalize();
        if(!target.startsWith(workspace)||Files.isSymbolicLink(workspace))throw new CheckFailure("UNSAFE_CHECK_PATH");
        Path cursor=workspace;for(Path component:workspace.relativize(target)){cursor=cursor.resolve(component);if(Files.isSymbolicLink(cursor))throw new CheckFailure("UNSAFE_CHECK_PATH");}
        var attrs=Files.readAttributes(target,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attrs.isRegularFile()||attrs.size()>MAX_FILE_BYTES)throw new CheckFailure("UNBOUNDED_CHECK_FILE");
        byte[] bytes=Files.readAllBytes(target);if(bytes.length>MAX_FILE_BYTES)throw new CheckFailure("UNBOUNDED_CHECK_FILE");control.checkpoint();return bytes;
    }
    private static void copy(ObjectNode out,JsonNode in,String fields){for(var f:fields.split(" "))out.set(f,in.required(f));}
    private static void seconds(ObjectNode out,String to,JsonNode in,String from){out.put(to,in.required(from).asInt()/1000);}
    private static final class CheckFailure extends Exception { final String code;CheckFailure(String code){this.code=code;} }
    private static final class Issues {
        int count;final List<Map<String,String>> visible=new ArrayList<>();
        void add(String path,String pointer,String rule,String source){count++;if(visible.size()<8)visible.add(Map.of("path",path,"pointer",pointer,"rule",rule,"source",source));}
        String feedback() throws Exception {
            var shown=new ArrayList<>(visible);String value;
            do {value=EvaluationArtifacts.JSON.writeValueAsString(Map.of("checkId",ID,"issueCount",count,"issues",shown,"expectedValuesIncluded",false));if(value.length()<=1900)return value;shown.removeLast();}while(!shown.isEmpty());
            return "{\"checkId\":\"PUBLIC_WORKFLOW_ACCEPTANCE_V1\",\"issuesOmitted\":true}";
        }
    }
}
