package com.clawkit.evaluation.context;

import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;

/** Detached regrade from sealed bytes; NOT_RUN and unavailable usage never close the live-full gate. */
final class LiveFullAudit {
    record Result(int planned,int attempted,int notRun,boolean artifactIntegrity,boolean outcomesAgree,
        boolean liveFullGateSatisfied,boolean semanticSourceReviewComplete,List<Map<String,Object>> recomputed) {}
    static Result recompute(Path root) throws Exception {
        var spec=LiveFullSpec.parse(EvaluationArtifacts.JSON.readTree(root.resolve("frozen-data.json").toFile()));
        var manifest=EvaluationArtifacts.JSON.readTree(root.resolve("manifest.json").toFile());
        var hashes=EvaluationArtifacts.JSON.readValue(root.resolve("artifact-hashes.json").toFile(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>(){});
        boolean integrity=hashes.equals(EvaluationArtifacts.hashes(root))&&manifest.path("datasetHash").asText().equals(EvaluationArtifacts.sha256(Files.readAllBytes(root.resolve("frozen-data.json"))))
            &&EvaluationArtifacts.JSON.readTree(root.resolve("source-integrity.json").toFile()).path("unchanged").asBoolean(false);
        var fields=manifest.path("sourceHashes").fields();while(fields.hasNext()){var e=fields.next();ContinuationSpec.relativeFile(e.getKey());integrity &= e.getValue().asText().equals(EvaluationArtifacts.sha256(Files.readAllBytes(root.resolve("source-inputs/"+e.getKey()))));}
        integrity &= manifest.path("contextVariants").path("baselineManifestHash").asText().equals(FrozenContextVariants.MANIFEST_HASH)
            &&manifest.path("contextVariants").path("originalJarHash").asText().equals(EvaluationArtifacts.sha256(Files.readAllBytes(root.resolve("baseline/original-context.jar"))));
        var rows=new LinkedHashMap<String,JsonNode>();for(var line:Files.readAllLines(root.resolve("instances.jsonl")))if(!line.isBlank()){var row=EvaluationArtifacts.JSON.readTree(line);if(rows.putIfAbsent(row.path("id").asText(),row)!=null)throw new IllegalStateException("duplicate full row");}
        if(!new ArrayList<>(rows.keySet()).equals(spec.instances().stream().map(LiveFullSpec.Instance::id).toList()))throw new IllegalStateException("full denominator/order differs");
        int attempted=0,notRun=0,candidateQualified=0;boolean agrees=true;var results=new ArrayList<Map<String,Object>>();
        for(var instance:spec.instances()){
            var row=rows.get(instance.id());if(!row.path("taskId").asText().equals(instance.taskId())||!row.path("arm").asText().equals(instance.arm().name()))throw new IllegalStateException("full assignment differs");
            if(row.path("status").asText().equals("NOT_RUN")){if(Files.exists(root.resolve("instances/"+instance.id()+"/calls")))throw new IllegalStateException("NOT_RUN includes calls");notRun++;results.add(Map.of("id",instance.id(),"status","NOT_RUN"));continue;}
            attempted++;var evidence=LiveFullEvidence.recompute(root,spec,instance);var recorded=EvaluationArtifacts.JSON.treeToValue(row.path("evidence"),LiveFullEvidence.Result.class);
            boolean matches=evidence.equals(recorded);boolean success=evidence.liveFullGateSatisfied()&&row.path("failureType").isNull();
            agrees &= matches&&success==row.path("status").asText().equals("PASS")&&evidence.requestContract()&&evidence.withinLimits();
            if(success&&instance.arm()==ContinuationSpec.Arm.C3_CANDIDATE_CONTEXT)candidateQualified++;
            results.add(Map.of("id",instance.id(),"evidence",evidence,"recordedEvidenceMatches",matches));
        }
        return new Result(6,attempted,notRun,integrity,agrees,integrity&&agrees&&candidateQualified==2,false,List.copyOf(results));
    }
}
