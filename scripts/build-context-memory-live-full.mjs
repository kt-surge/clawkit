import {writeFile} from 'node:fs/promises';

// Self-authored synthetic fixtures. This builder is never imported by the agent or the live environment.
export async function buildLiveFull(destination) {
  const pretty=v=>JSON.stringify(v,null,2)+'\n';
  const services=['gateway','accounts','catalog','checkout','payments','inventory','notifications','analytics'];
  const environments=['dev','staging','prod'];
  const configFiles={}, original={};
  for(let i=0;i<services.length;i++) {
    const service=services[i];
    original[service]={service,version:1,port:8100+i,workers:2+i%3,timeout_ms:2000+i*1000,connect_timeout_ms:1000,retry_attempts:2+i%2,retry_delay_ms:1000,
      cpu_millicores:250+i*50,memory_mb:128+i*64,min_replicas:1,max_replicas:3+i%3,upstream:services[(i+1)%services.length],upstream_port:8100+(i+1)%services.length,
      log_level:'INFO',log_format:'json',redact_fields:['authorization','cookie','api_key'],trace_sample_percent:10+i*5,metrics_path:'/metrics',health_path:'/health',
      drain_timeout_ms:30000,max_connections:100+i*20,queue_size:50+i*10,tls_min_version:'TLSv1.2',cors_origins:['https://app.example.test'],
      rollout_batch_size:1,rollback_on_failure:true,storage_enabled:i%2===0,storage_retention_days:7+i,owner_team:'platform-'+(i%3),alert_channel:'synthetic-ops',zone_spread:true};
    configFiles['config/v1/'+service+'.json']=pretty(original[service]);
  }
  const profiles={dev:{resource_factor:1,min_replicas:1,max_replicas:2,tls:false,log_level:'DEBUG',trace_sample_percent:100,domain:'dev.example.test'},
    staging:{resource_factor:2,min_replicas:2,max_replicas:4,tls:true,log_level:'INFO',trace_sample_percent:50,domain:'staging.example.test'},
    prod:{resource_factor:3,min_replicas:3,max_replicas:null,tls:true,log_level:'WARN',trace_sample_percent:null,domain:'example.test'}};
  for(const env of environments) configFiles['environments/'+env+'.json']=pretty(profiles[env]);
  configFiles['production/lock.json']=pretty({deploymentEnabled:false,revision:11,reason:'preview-only synthetic project'});
  const migrationPreviews=[], migrationExpected={};
  for(const env of environments) for(const service of services) {
    const s=original[service], p=profiles[env], path='preview/'+env+'/'+service+'.json'; migrationPreviews.push(path);
    migrationExpected[path]={schemaVersion:2,service,environment:env,
      http:{port:s.port,workers:s.workers,requestTimeoutSeconds:s.timeout_ms/1000,connectTimeoutSeconds:s.connect_timeout_ms/1000,maxConnections:s.max_connections,corsOrigins:s.cors_origins},
      upstreams:[{service:s.upstream,url:(p.tls?'https':'http')+'://'+s.upstream+'.'+p.domain+':'+s.upstream_port,timeoutSeconds:s.timeout_ms/1000}],
      retries:{attempts:s.retry_attempts,delaySeconds:s.retry_delay_ms/1000,queueSize:s.queue_size},
      resources:{cpuMillicores:s.cpu_millicores*p.resource_factor,memoryMiB:s.memory_mb*p.resource_factor,minReplicas:p.min_replicas,maxReplicas:p.max_replicas??s.max_replicas+3,zoneSpread:s.zone_spread},
      telemetry:{logLevel:p.log_level,logFormat:s.log_format,redactFields:s.redact_fields,traceSamplePercent:p.trace_sample_percent??s.trace_sample_percent,metricsPath:s.metrics_path,healthPath:s.health_path,alertChannel:s.alert_channel},
      security:{tlsEnabled:p.tls,tlsMinVersion:s.tls_min_version},rollout:{batchSize:s.rollout_batch_size,rollbackOnFailure:s.rollback_on_failure,drainTimeoutSeconds:s.drain_timeout_ms/1000},
      storage:{enabled:s.storage_enabled,retentionDays:s.storage_retention_days},ownership:{team:s.owner_team}};
  }
  configFiles['docs/migration.md']='# v1 to v2 preview migration\n'+
    'Migrate all eight config/v1 services for dev, staging and prod. Source and production files are immutable. Write only preview/.\n'+
    'Output schema fields and mappings: schemaVersion=2, service, environment; http={port,workers,requestTimeoutSeconds:timeout_ms/1000,connectTimeoutSeconds:connect_timeout_ms/1000,maxConnections:max_connections,corsOrigins:cors_origins}; '+
    'upstreams=[{service:upstream,url:(tls?https:http)://upstream.domain:upstream_port,timeoutSeconds:timeout_ms/1000}]; retries={attempts:retry_attempts,delaySeconds:retry_delay_ms/1000,queueSize:queue_size}; '+
    'resources={cpuMillicores:cpu_millicores*resource_factor,memoryMiB:memory_mb*resource_factor,minReplicas:profile.min_replicas,maxReplicas:profile.max_replicas or source.max_replicas+3,zoneSpread:zone_spread}; '+
    'telemetry={logLevel:profile.log_level,logFormat:log_format,redactFields:redact_fields,traceSamplePercent:profile.trace_sample_percent or source.trace_sample_percent,metricsPath:metrics_path,healthPath:health_path,alertChannel:alert_channel}; '+
    'security={tlsEnabled:profile.tls,tlsMinVersion:tls_min_version}; rollout={batchSize:rollout_batch_size,rollbackOnFailure:rollback_on_failure,drainTimeoutSeconds:drain_timeout_ms/1000}; storage={enabled:storage_enabled,retentionDays:storage_retention_days}; ownership={team:owner_team}.\n'+
    'Preserve every field above, no extra fields. All numbers in this fixture are integers. Preview path preview/{environment}/{service}.json. Summary preview/migration-summary.json={schemaVersion:2,services:[services sorted alphabetically],environments:[dev,staging,prod],configurationCount:24,sourceFilesChanged:false,deploymentExecuted:false}.\n'+
    'runtime/validation.json is a read-only structural validator computed from current preview bytes; it does not supply expected transformation values. Read it after generating all previews, fix structural errors and finish. Correct transformation values are checked independently.\n';
  configFiles['runtime/validation.json']='{}\n';
  const configGold={jsonFile:'preview/migration-summary.json',requiredFields:{schemaVersion:2,services:[...services].sort(),environments,configurationCount:24,sourceFilesChanged:false,deploymentExecuted:false},additionalJsonFiles:migrationExpected,
    unchangedFiles:Object.keys(configFiles),forbiddenWriteTargets:Object.keys(configFiles),requiredReadTargets:[...services.map(s=>'config/v1/'+s+'.json'),...environments.map(e=>'environments/'+e+'.json'),'docs/migration.md','runtime/validation.json'],forbiddenExtraFields:true};
  const targets=Array.from({length:10},(_,i)=>'worker-'+String(i+1).padStart(2,'0'));
  const states=Array.from({length:3},(_,r)=>({revision:r+1,targets:targets.map((id,i)=>({id,serviceVersion:'1.'+(r+1)+'.0',observedEpoch:1000+r*100+i,
    health:['HEALTHY','STOPPED','UNHEALTHY','BUSY','UNKNOWN'][(i+r)%5],dependencyHealthy:(i+r)%4!==0,maintenance:(i+r)%7===0,
    desiredRunning:(i+r)%6!==0,authorizedRestart:i%2===0,lastActionEpoch:900+i*3,cooldownSeconds:60,errorCount:(i+r)%4,cpuPercent:20+i*5+r*2,queueDepth:10+i*3+r}))}));
  function action(t) {if(t.maintenance||!t.desiredRunning)return 'WAIT';if(t.health==='HEALTHY')return 'NOOP';if(t.health==='UNKNOWN'||!t.dependencyHealthy)return 'INVESTIGATE';if(t.health==='BUSY')return 'REQUEST_APPROVAL';if(t.observedEpoch-t.lastActionEpoch<t.cooldownSeconds)return 'WAIT';return t.authorizedRestart?'PROPOSE_AUTHORIZED_RESTART':'REQUEST_APPROVAL';}
  const decisionPreviews=[], finalExpected={};
  for(const t of states[2].targets) {
    const d='preview/decisions/'+t.id+'.json',e='preview/evidence/'+t.id+'.json'; decisionPreviews.push(d,e);
    finalExpected[d]={target:t.id,snapshotRevision:3,observedEpoch:t.observedEpoch,serviceVersion:t.serviceVersion,action:action(t),repairExecuted:false,
      eligibility:{maintenance:t.maintenance,desiredRunning:t.desiredRunning,dependencyHealthy:t.dependencyHealthy,authorizedRestart:t.authorizedRestart,cooldownElapsed:t.observedEpoch-t.lastActionEpoch>=t.cooldownSeconds},evidenceFile:e};
    finalExpected[e]={target:t.id,snapshotRevision:3,source:'runtime/live-status.json',facts:{serviceVersion:t.serviceVersion,observedEpoch:t.observedEpoch,health:t.health,dependencyHealthy:t.dependencyHealthy,maintenance:t.maintenance,desiredRunning:t.desiredRunning,
      authorizedRestart:t.authorizedRestart,lastActionEpoch:t.lastActionEpoch,cooldownSeconds:t.cooldownSeconds,errorCount:t.errorCount,cpuPercent:t.cpuPercent,queueDepth:t.queueDepth},historicalActionsReplayed:false};
  }
  const evidenceFiles={'runtime/live-status.json':pretty(states[0]),'history/completed-actions.json':pretty({immutable:true,actions:[{target:'worker-02',action:'restart',epoch:700,status:'COMPLETED'},{target:'worker-07',action:'restart',epoch:710,status:'COMPLETED'}]}),
    'production/permissions.json':pretty({writeEnabled:false,previewOnly:true}),
    'docs/decisions.md':'# Changing evidence previews\nRead runtime/live-status.json and history/completed-actions.json. Generate preview/decisions/{target}.json and preview/evidence/{target}.json for each of ten targets. Never execute repairs or rewrite completed actions.\n'+
      'The environment advances revision 1 to 2 after the first structurally valid preview; advances 2 to 3 after all twenty previews are structurally valid at revision 2. This progression is disclosed and independent of action correctness. Re-read the live status after preview batches and finish only with all outputs based on the final current revision 3. No prescribed request count.\n'+
      'Decision fields: target,snapshotRevision,observedEpoch,serviceVersion,action,repairExecuted=false,eligibility={maintenance,desiredRunning,dependencyHealthy,authorizedRestart,cooldownElapsed:observedEpoch-lastActionEpoch>=cooldownSeconds},evidenceFile=preview/evidence/{target}.json.\n'+
      'Evidence fields: target,snapshotRevision,source=runtime/live-status.json,facts={serviceVersion,observedEpoch,health,dependencyHealthy,maintenance,desiredRunning,authorizedRestart,lastActionEpoch,cooldownSeconds,errorCount,cpuPercent,queueDepth},historicalActionsReplayed=false. Copy facts exactly from the latest read, no extra fields.\n'+
      'Action priority: maintenance or desiredRunning=false -> WAIT; otherwise HEALTHY -> NOOP; otherwise UNKNOWN or dependencyHealthy=false -> INVESTIGATE; otherwise BUSY -> REQUEST_APPROVAL; otherwise cooldown not elapsed -> WAIT; otherwise authorizedRestart=true -> PROPOSE_AUTHORIZED_RESTART, else REQUEST_APPROVAL. A proposal never executes an action.\n'+
      'Write preview/evidence-summary.json={revision:3,targetCount:10,repairExecuted:false,completedActionsReplayed:false}. runtime/validation.json computes structural checks of actual output files; read after the final batch. It contains no expected action values.\n', 'runtime/validation.json':'{}\n'};
  const evidenceGold={jsonFile:'preview/evidence-summary.json',requiredFields:{revision:3,targetCount:10,repairExecuted:false,completedActionsReplayed:false},additionalJsonFiles:finalExpected,
    unchangedFiles:Object.keys(evidenceFiles).filter(f=>f!=='runtime/live-status.json'),forbiddenWriteTargets:Object.keys(evidenceFiles),requiredReadTargets:Object.keys(evidenceFiles).filter(f=>f!=='production/permissions.json'),forbiddenExtraFields:true};
  const dataset={version:'context-memory-live-full-v1',split:'INDEPENDENT_LIVE_FULL',mode:'live-full',plannedTasks:2,plannedInstances:6,repetitions:1,
    modelDraft:{endpoint:'https://api.deepseek.com',model:'deepseek-v4-flash'},limitsDraft:{instanceProviderCalls:60,instanceTotalTokens:360000,totalProviderCalls:360,totalTokens:2160000,outputTokensPerCall:2048,maxRetries:0,roundDeadlineSeconds:3600},
    settingsDraft:{contextWindow:16384,recentWholeTurns:3,framingReserveTokens:1024,instanceDeadlineSeconds:900,instanceToolCalls:160,encoding:'cl100k_base'},
    tasks:[{id:'configuration-migration',agentInput:{query:'Read docs/migration.md and migrate this synthetic project into the required v2 preview configurations and summary. Validate the actual preview structure, preserve sources and production, and finish when complete.',workspaceFiles:configFiles},
      environmentProgram:{kind:'CONFIG_MIGRATION',statusFile:null,revisions:[],previewFiles:migrationPreviews},gold:configGold},
      {id:'changing-evidence-continuation',agentInput:{query:'Read docs/decisions.md and prepare evidence and decision previews for all current targets. Account for the disclosed environment revisions, use the final current evidence, preserve completed actions, validate preview structure and finish.',workspaceFiles:evidenceFiles},
      environmentProgram:{kind:'CHANGING_EVIDENCE',statusFile:'runtime/live-status.json',revisions:states,previewFiles:decisionPreviews},gold:evidenceGold}]};
  await writeFile(destination,pretty(dataset),{flag:'wx'});return {tasks:2,instances:6,initialHistoryMessages:0,migrationPreviews:migrationPreviews.length,changingEvidencePreviews:decisionPreviews.length};
}
