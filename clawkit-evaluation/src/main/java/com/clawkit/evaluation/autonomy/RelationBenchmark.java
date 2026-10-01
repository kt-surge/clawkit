package com.clawkit.evaluation.autonomy;

import com.clawkit.ops.delivery.managed.AlertmanagerInput;
import com.clawkit.ops.loop.managed.*;
import com.clawkit.ops.mcp.IsolatedComposeClient;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static com.clawkit.evaluation.autonomy.AutonomyBenchmark.JSON;

/** Fixed, controlled correlation contract. Actual dependency pins; unrelated/cross-environment bindings are synthetic. */
final class RelationBenchmark {
    record Pair(String left,String right,boolean expectedRelated) {}
    static List<Pair> cases() {
        return List.of(new Pair("orders","stock",true),new Pair("orders","unrelated",false),new Pair("orders","crossenv",false),
            new Pair("orders","late",false),new Pair("stock","unrelated",false),new Pair("stock","crossenv",false));
    }
    static Map<String,Object> evaluate(Path root,IsolatedComposeClient.Target target,Clock clock) throws Exception {
        var store=new ManagedTriggerStore(root,clock); String token="benchmark-only-local-source-token-20261001";
        store.bind(registration("orders",target.project(),"orders",target.containerId(),target.dependencies(),target,clock),token);
        store.bind(registration("stock",target.project(),"stock",target.dependencies().get("stock"),Map.of(),target,clock),token);
        store.bind(registration("unrelated",target.project(),"billing","c".repeat(64),Map.of(),target,clock),token);
        store.bind(registration("crossenv",target.project()+"-stage","orders","d".repeat(64),Map.of(),target,clock),token);
        var input=new AlertmanagerInput(store,clock); var at=clock.instant();
        var labels=List.of("orders","stock","unrelated","crossenv","late");
        var eventIds=new HashMap<String,String>();
        for(int i=0;i<labels.size();i++) {
            String label=labels.get(i),service=label.equals("late") ? "stock" : label.equals("unrelated") ? "billing" : label.equals("crossenv") ? "orders" : label;
            String environment=label.equals("crossenv") ? target.project()+"-stage" : target.project();
            var node=JSON.createObjectNode().put("version","4").put("groupKey","shared-notification-group").put("truncatedAlerts",0).put("status","firing");
            var alert=node.putArray("alerts").addObject().put("status","firing").put("fingerprint",String.format("%016x",i+1))
                .put("startsAt",at.minusSeconds(label.equals("late") ? 400 : 20+i).toString()).put("endsAt","0001-01-01T00:00:00Z");
            alert.putObject("labels").put("clawkit_environment",environment).put("clawkit_service",service).put("alertname","FrozenSourceEvent");
            alert.putObject("annotations").put("summary","Controlled source claim; not a fresh health observation");
            byte[] payload=JSON.writeValueAsBytes(node); input.accept(payload); if(label.equals("orders")) input.accept(payload);
            var entry=store.snapshot().entries().stream().filter(e -> e.trigger().sourceFingerprint().equals(String.format("%016x",labels.indexOf(label)+1))).findFirst().orElseThrow();
            eventIds.put(label,entry.trigger().eventId()); store.complete(entry.trigger().eventId(),"inc-"+label,false);
        }
        var snapshot=store.snapshot(); var rows=new ArrayList<Map<String,Object>>(); int tp=0,fp=0,fn=0;
        for(var pair:cases()) {
            boolean predicted=snapshot.relations().stream().filter(r -> r.state()==IncidentRelation.State.ACTIVE).anyMatch(r ->
                Set.of(r.leftEventId(),r.rightEventId()).equals(Set.of(eventIds.get(pair.left()),eventIds.get(pair.right()))));
            if(predicted && pair.expectedRelated()) tp++; else if(predicted) fp++; else if(pair.expectedRelated()) fn++;
            rows.add(Map.of("pair",pair,"predicted",predicted,"passed",predicted==pair.expectedRelated()));
        }
        return Map.ofEntries(Map.entry("evidenceKind","CONTROLLED_SOURCE_AND_BINDING_CONTRACT_NOT_REAL_ALERTMANAGER_OR_CAUSAL_ROOT_CAUSE"),
            Map.entry("pairs",rows),Map.entry("truePositive",tp),Map.entry("falsePositive",fp),Map.entry("falseNegative",fn),
            Map.entry("precision",tp+fp==0 ? 0.0 : (double)tp/(tp+fp)),Map.entry("recall",tp+fn==0 ? 0.0 : (double)tp/(tp+fn)),
            Map.entry("ordersDeliveries",snapshot.entries().stream().filter(e -> e.trigger().eventId().equals(eventIds.get("orders"))).findFirst().orElseThrow().deliveries()),
            Map.entry("snapshot",snapshot));
    }
    private static ManagedRegistrationStore.Registration registration(String id,String environment,String service,String container,Map<String,String> dependencies,IsolatedComposeClient.Target source,Clock clock) {
        var app=new ManagedApplication(id,"local-isolated",environment,service,1,true,ManagedApplication.DesiredState.RUNNING,null,
            URI.create("http://127.0.0.1:18380/health"),URI.create("http://127.0.0.1:18380/business"),"accepted",Duration.ofSeconds(5),Duration.ofSeconds(90));
        var target=new IsolatedComposeClient.Target(source.context(),source.daemonId(),source.endpoint(),source.composeFile(),source.composeHash(),environment,service,container,dependencies);
        return new ManagedRegistrationStore.Registration(app,ActionPolicy.ask(app,clock.instant().plusSeconds(600)),target,null);
    }
}
