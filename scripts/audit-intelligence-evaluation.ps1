param([Parameter(Mandatory=$true)][string]$RunDir,[Parameter(Mandatory=$true)][string]$RepoDir)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$runRoot=(Resolve-Path -LiteralPath $RunDir).Path
$repoRoot=(Resolve-Path -LiteralPath $RepoDir).Path
function Read-Json([string]$Path) { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function Require([bool]$Condition,[string]$Message) { if(-not $Condition){ throw $Message } }
function Sha-Text([string]$Text) {
    $sha=[System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() } finally { $sha.Dispose() }
}
$plan=Read-Json (Join-Path $runRoot 'frozen-plan.json')
$summary=Read-Json (Join-Path $runRoot 'summary.json')
$metadata=Read-Json (Join-Path $runRoot 'metadata.json')
$rows=@(Get-Content -LiteralPath (Join-Path $runRoot 'instances.jsonl') | ForEach-Object { $_ | ConvertFrom-Json })
Require ($rows.Count -eq @($plan.orderedInstances).Count) 'Missing planned instance in primary denominator'
Require (@($rows.id | Select-Object -Unique).Count -eq $rows.Count) 'Duplicate result ID'
$calls=0L; $tokens=0L; $unavailable=0L; $dispatches=0; $details=@(); $parameters=@(); $toolSets=@(); $models=@()
foreach($row in $rows) {
    $trial=@($plan.orderedInstances | Where-Object id -eq $row.id)
    Require ($trial.Count -eq 1) 'Result outside frozen plan'
    $scenario=$trial[0].scenario
    $instanceRoot=Join-Path $runRoot ('instances/'+$row.id)
    $exchangePath=Join-Path $instanceRoot 'model-exchanges.json'
    $exchanges=@(if(Test-Path -LiteralPath $exchangePath){ Read-Json $exchangePath })
    $actual=0L; $unknown=0L
    foreach($exchange in $exchanges) {
        $response=if($null -ne $exchange.response){ $exchange.response } else { $exchange.rejectedResponse }
        if($null -ne $response -and $response.usage.source -eq 'ACTUAL'){ $actual+=[long]$response.usage.totalTokens } else { $unknown++ }
        $parameters+=($exchange.request.parameters | ConvertTo-Json -Depth 20 -Compress)
        $toolSets+=Sha-Text ($exchange.request.tools | ConvertTo-Json -Depth 30 -Compress)
        if($null -ne $response -and $null -ne $response.metadata -and $null -ne $response.metadata.PSObject.Properties['model']){ $models+=$response.metadata.PSObject.Properties['model'].Value }
    }
    Require ($exchanges.Count -eq $row.requests) ('Paid requests mismatch: '+$row.id)
    Require ($actual -eq $row.actualTokens -and $unknown -eq $row.unavailableUsage) ('Actual usage mismatch: '+$row.id)
    $calls+=$exchanges.Count; $tokens+=$actual; $unavailable+=$unknown
    $dispatchCount=if(Test-Path -LiteralPath $instanceRoot){ @(Get-ChildItem -LiteralPath $instanceRoot -Filter 'dispatch-*.json').Count } else { 0 }
    Require ($dispatchCount -eq $row.actions) ('Dispatch mismatch: '+$row.id)
    $dispatches+=$dispatchCount
    if($row.origin -eq 'SYSTEM') { Require (-not $row.diagnosisPass -and -not $row.choicePass -and -not $row.rootCauseHit) 'System fallback counted as model success' }
    if(Test-Path -LiteralPath (Join-Path $instanceRoot 'external-oracle.json')) {
        $oracle=Read-Json (Join-Path $instanceRoot 'external-oracle.json')
        Require (@($oracle.exactBusinessSamples).Count -eq 3) 'Missing independent three-sample oracle'
        $healthy=@($oracle.exactBusinessSamples | Where-Object { -not $_ }).Count -eq 0
        Require ($healthy -eq $row.externalRecovered) 'External recovery projection mismatch'
    }
    if(Test-Path -LiteralPath (Join-Path $instanceRoot 'outcome.json')) {
        $outcome=Read-Json (Join-Path $instanceRoot 'outcome.json')
        if($row.origin -ne 'SYSTEM' -and $null -ne $outcome.diagnosis) {
            $supported=@($outcome.diagnosis.hypotheses | Where-Object assessment -eq 'SUPPORTED')
            $primary=if($supported.Count -gt 0){ $supported[0].cause } else { $outcome.diagnosis.hypotheses[0].cause }
            Require ($primary -eq $row.primaryCause) 'Primary cause ordering mismatch'
            $hit=$scenario.cause -ne 'UNKNOWN' -and $primary -eq $scenario.cause
            Require ($hit -eq $row.rootCauseHit) 'Root cause success disagrees with hidden label'
        }
    }
    if($row.state -eq 'RECOVERED' -and $row.actions -eq 1) {
        $attempts=Read-Json (Join-Path $instanceRoot 'execution/attempts/snapshot.json')
        Require (@($attempts.attempts).Count -eq 1 -and $attempts.attempts[0].state -eq 'VERIFIED_SUCCESS') 'Recovery lacks durable verified attempt'
        $authorization=Read-Json (Join-Path $instanceRoot ('execution/control/authorization-'+$attempts.attempts[0].attemptId+'.json'))
        Require ($authorization.source -eq 'POLICY') 'Wrong repair authorization source'
    }
    $details+=@{id=$row.id;result=$row.result;requests=$exchanges.Count;actualTokens=$actual;actions=$dispatchCount}
}
Require ($calls -eq $summary.requests -and $tokens -eq $summary.actualTokens) 'Aggregate raw usage mismatch'
$uniqueParameters=@($parameters | Select-Object -Unique); $uniqueTools=@($toolSets | Select-Object -Unique)
Require ($uniqueParameters.Count -le 1 -and $uniqueTools.Count -le 1) 'Model arms have different parameters or tool contracts'
$sourceFiles=Read-Json (Join-Path $runRoot 'source-files.json')
foreach($file in $sourceFiles.PSObject.Properties) {
    Require ((Get-FileHash -LiteralPath (Join-Path $repoRoot $file.Name) -Algorithm SHA256).Hash.ToLowerInvariant() -eq $file.Value) ('Frozen source changed: '+$file.Name)
}
$integrity=Read-Json (Join-Path $runRoot 'source-integrity.json')
$cleanup=Read-Json (Join-Path $runRoot 'cleanup-audit.json')
Require ($integrity.unchanged -and $cleanup.remainingZero) 'Source integrity or owned cleanup failed'
$relation=Read-Json (Join-Path $runRoot 'relations.json')
Require (@($relation.pairs).Count -eq @($plan.relationCases).Count) 'Relation denominator differs from plan'
$audit=@{ auditPassed=$true; instances=$rows.Count; providerRequests=$calls; actualTokens=$tokens; unavailableUsage=$unavailable; dispatches=$dispatches;
    sharedParameters=$uniqueParameters; sharedToolSets=$uniqueTools.Count; actualModels=@($models | Where-Object { $null -ne $_ } | Select-Object -Unique);
    sourceFiles=@($sourceFiles.PSObject.Properties).Count; relationPairs=@($relation.pairs).Count; failedRelationPairs=@($relation.pairs | Where-Object { -not $_.passed }).Count;
    detail=$details; apiCost=$null; auditAt=[DateTime]::UtcNow.ToString('o') }
[System.IO.File]::WriteAllText((Join-Path $runRoot 'audit.json'),($audit | ConvertTo-Json -Depth 20),[System.Text.UTF8Encoding]::new($false))
$audit | Select-Object auditPassed,instances,providerRequests,actualTokens,unavailableUsage,dispatches,sharedToolSets,relationPairs,failedRelationPairs | ConvertTo-Json
