<#
.SYNOPSIS
  Development smoke of the extracted product package with actual local containers and actual model.
  Creates a new owned project, checks POLICY and HUMAN execution, and preserves every artifact/failure.
  Does not enable notifications, publish, or touch existing Compose projects.
#>
param(
    [Parameter(Mandatory=$true)][string]$PackageDir,
    [Parameter(Mandatory=$true)][string]$OutputDir,
    [string]$DockerContext="desktop-linux",
    [string]$Model="deepseek-v4-flash"
)
$ErrorActionPreference="Stop"
Set-StrictMode -Version Latest
if ($PSVersionTable.PSVersion.Major -lt 7) { throw "This verification helper needs PowerShell 7; the product launcher itself does not." }
$packageRoot=(Resolve-Path -LiteralPath $PackageDir).Path
$outputRoot=[System.IO.Path]::GetFullPath($OutputDir)
if (Test-Path -LiteralPath $outputRoot) { throw "Use a fresh output directory; never overwrite previous failures." }
New-Item -ItemType Directory -Path $outputRoot | Out-Null
$fixture=(Resolve-Path -LiteralPath (Join-Path $packageRoot "ops-fixtures/layered-autonomy/compose.yaml")).Path
$jar=(Resolve-Path -LiteralPath (Join-Path $packageRoot "clawkit.jar")).Path
$java=(Get-Command java -ErrorAction Stop).Source
$docker=(Get-Command docker -ErrorAction Stop).Source
$stateRoot=Join-Path $outputRoot "state"
$project="clawkit-autonomy-package-"+[guid]::NewGuid().ToString("N").Substring(0,12)
$owned=$false
$controlProcess=$null
$success=$false

function Write-Json([string]$Name,$Value) {
    [System.IO.File]::WriteAllText((Join-Path $outputRoot $Name),($Value | ConvertTo-Json -Depth 40),[System.Text.UTF8Encoding]::new($false))
}
function Start-OwnedProcess([string]$Executable,[string[]]$Arguments) {
    $info=[System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$Executable
    $info.UseShellExecute=$false
    $info.CreateNoWindow=$true
    $info.WindowStyle=[System.Diagnostics.ProcessWindowStyle]::Hidden
    $info.WorkingDirectory=$packageRoot
    $info.RedirectStandardOutput=$true
    $info.RedirectStandardError=$true
    $info.StandardOutputEncoding=[System.Text.Encoding]::UTF8
    $info.StandardErrorEncoding=[System.Text.Encoding]::UTF8
    foreach ($item in $Arguments) { $info.ArgumentList.Add($item) }
    $process=[System.Diagnostics.Process]::Start($info)
    return @{ Process=$process; Output=$process.StandardOutput.ReadToEndAsync(); Error=$process.StandardError.ReadToEndAsync() }
}
function Finish-OwnedProcess($Handle,[string]$Name,[int]$TimeoutSeconds) {
    if (-not $Handle.Process.WaitForExit($TimeoutSeconds*1000)) {
        $Handle.Process.Kill($true)
        $Handle.Process.WaitForExit()
        Write-Json ($Name+".json") @{ exitCode=$Handle.Process.ExitCode; timedOut=$true; stdout=$Handle.Output.GetAwaiter().GetResult(); stderr=$Handle.Error.GetAwaiter().GetResult() }
        throw "Owned process timed out: $Name"
    }
    $result=@{ exitCode=$Handle.Process.ExitCode; timedOut=$false; stdout=$Handle.Output.GetAwaiter().GetResult(); stderr=$Handle.Error.GetAwaiter().GetResult() }
    Write-Json ($Name+".json") $result
    if ($result.exitCode -ne 0) { throw "Command failed: $Name (exit $($result.exitCode)); inspect its preserved artifact." }
    return $result
}
function Invoke-Owned([string]$Executable,[string[]]$Arguments,[string]$Name,[int]$TimeoutSeconds=60) {
    $handle=Start-OwnedProcess $Executable $Arguments
    try { return Finish-OwnedProcess $handle $Name $TimeoutSeconds } finally { $handle.Process.Dispose() }
}
function Invoke-Package([string[]]$Arguments,[string]$Name,[int]$TimeoutSeconds=60) {
    return Invoke-Owned -Executable $java -Arguments (@('-Dfile.encoding=UTF-8','-jar',$jar,'autonomy')+$Arguments+@('--state-dir',$stateRoot)) -Name $Name -TimeoutSeconds $TimeoutSeconds
}
function Read-SharedJson([string]$Path) {
    $stream=[System.IO.FileStream]::new($Path,[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,
        ([System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete))
    $reader=[System.IO.StreamReader]::new($stream,[System.Text.Encoding]::UTF8)
    try { return ($reader.ReadToEnd() | ConvertFrom-Json) } finally { $reader.Dispose(); $stream.Dispose() }
}
function Read-Snapshot { return Read-SharedJson (Join-Path $stateRoot 'orders/controller/orders.snapshot.json') }
function Wait-Incident([string]$Expected,[int]$TimeoutSeconds=180) {
    $end=[DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTime]::UtcNow -lt $end) {
        if ($null -ne $controlProcess -and $controlProcess.Process.HasExited) { throw "Control process exited before $Expected" }
        $snapshotFile=Join-Path $stateRoot 'orders/controller/orders.snapshot.json'
        if (Test-Path -LiteralPath $snapshotFile) {
            $snapshot=Read-Snapshot
            if ($null -ne $snapshot.current -and $snapshot.current.state -eq $Expected) { return $snapshot }
            if ($null -ne $snapshot.current -and $snapshot.current.state -eq 'HANDOFF') { throw "Unexpected handoff; inspect the current snapshot." }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Incident did not reach $Expected within its verification deadline."
}
function New-LoopbackPort {
    $listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}
function Assert-Business([int]$Port) {
    $result=Invoke-RestMethod -Uri "http://127.0.0.1:$Port/orders" -TimeoutSec 3
    if ($result.service -ne 'orders' -or $result.orderId -ne 'sample-001' -or $result.status -ne 'accepted' -or @($result.PSObject.Properties).Count -ne 3) {
        throw "Independent exact business JSON oracle failed"
    }
    return $true
}
function Inject-Fault([int]$Port) {
    $null=Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/__fixture/fault" -ContentType application/json -Body '{"seconds":3600}' -TimeoutSec 3
}
function Repair-Evidence($Snapshot,[string]$ExpectedSource) {
    $repair=Read-SharedJson (Join-Path (Join-Path $stateRoot 'orders/controller') $Snapshot.current.repair.artifact)
    if ($repair.status -ne 'RECOVERED' -or $repair.attemptState -ne 'VERIFIED_SUCCESS' -or @($repair.verification.samples).Count -lt 3) { throw "Repair lacks independent sustained verification" }
    $authorization=Read-SharedJson (Join-Path $stateRoot ("orders/execution/control/authorization-"+$repair.attemptId+".json"))
    if ($authorization.source -ne $ExpectedSource) { throw "Wrong authorization source; expected $ExpectedSource" }
    return @{ incidentId=$Snapshot.current.id; attemptId=$repair.attemptId; source=$authorization.source; sampleCount=@($repair.verification.samples).Count; action=$Snapshot.current.decision.playbook }
}

try {
    if ([string]::IsNullOrWhiteSpace($env:CLAWKIT_API_KEY)) { throw "CLAWKIT_API_KEY must be configured for this opt-in actual-model smoke" }
    $endpoint=Invoke-Owned -Executable $docker -Arguments @('context','inspect',$DockerContext,'--format','{{.Endpoints.docker.Host}}') -Name 'docker-endpoint'
    if ($endpoint.stdout.Trim() -notmatch '^(unix:///|npipe:////\./pipe/)') { throw "Only local Docker endpoints are authorized" }
    $os=Invoke-Owned -Executable $docker -Arguments @('--context',$DockerContext,'info','--format','{{.OSType}}') -Name 'docker-os'
    if ($os.stdout.Trim() -ne 'linux') { throw "Linux daemon required" }
    $existing=Invoke-Owned -Executable $docker -Arguments @('--context',$DockerContext,'ps','-a','--filter',"label=com.docker.compose.project=$project",'--format','{{.ID}}') -Name 'ownership-before-setup'
    if (-not [string]::IsNullOrWhiteSpace($existing.stdout)) { throw "Fresh project ownership check failed" }
    $ordersPort=New-LoopbackPort
    $catalogPort=New-LoopbackPort
    while ($ordersPort -eq $catalogPort) { $catalogPort=New-LoopbackPort }
    $env:AUTONOMY_ORDERS_PORT=[string]$ordersPort
    $env:AUTONOMY_CATALOG_PORT=[string]$catalogPort
    Write-Json 'metadata.json' @{ evidenceKind='ACTUAL_MODEL_ACTUAL_CONTAINERS_EXTRACTED_PRODUCT_DEVELOPMENT_SMOKE'; project=$project; context=$DockerContext;
        model=$Model; packageJar=$jar; jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant(); fixture=$fixture;
        perDecisionProviderCalls=6; perDecisionTokens=30000; perIncidentDecisions=3; providerRetries=0; notificationEnabled=$false; startedAt=[DateTime]::UtcNow.ToString('o') }
    $owned=$true
    $null=Invoke-Owned -Executable $docker -Arguments @('--context',$DockerContext,'compose','-f',$fixture,'-p',$project,'up','-d','--wait','--wait-timeout','60') -Name 'setup' -TimeoutSeconds 120
    $null=Assert-Business $ordersPort
    $null=Invoke-Package -Arguments @('register','orders','--compose',$fixture,'--context',$DockerContext,'--project',$project,'--service','orders','--dependencies','catalog','--stateless',
        '--health',"http://127.0.0.1:$ordersPort/health",'--business',"http://127.0.0.1:$ordersPort/orders",'--marker','accepted','--interval','2') -Name 'register'
    $null=Invoke-Package -Arguments @('check','orders') -Name 'config-check'
    $registration=Read-SharedJson (Join-Path $stateRoot 'orders/registration.json')
    if ($registration.policy.mode -ne 'ASK' -or $null -ne $registration.review) { throw "Registration did not default to ASK" }

    $null=Invoke-Package -Arguments @('policy','orders','limited-auto','--actions','start,restart','--minutes','30','--confirm-reviewed','--review-note','Disposable package fixture target and reviewed action scope confirmed') -Name 'qualified-policy'
    Inject-Fault $ordersPort
    $null=Invoke-Package -Arguments @('run','orders','--once','--model',$Model) -Name 'automatic-run' -TimeoutSeconds 180
    $auto=Read-Snapshot
    if ($auto.current.state -ne 'RECOVERED') { throw "Automatic package path did not recover" }
    $automaticEvidence=Repair-Evidence $auto 'POLICY'
    $null=Assert-Business $ordersPort
    Write-Json 'automatic-oracle.json' @{ recovered=$true; businessExactMatch=$true; evidence=$automaticEvidence }
    $null=Invoke-Package -Arguments @('run','orders','--once','--model',$Model) -Name 'healthy-repeat-run' -TimeoutSeconds 30
    $repeat=Read-Snapshot
    if ($repeat.current.id -ne $auto.current.id -or $repeat.current.decisions -ne $auto.current.decisions -or $repeat.current.repair.artifact -ne $auto.current.repair.artifact) { throw "Healthy restart repeated decision or repair" }
    $null=Invoke-Package -Arguments @('events','orders') -Name 'recent-events'

    $null=Invoke-Package -Arguments @('policy','orders','ask','--minutes','30') -Name 'ask-policy'
    Inject-Fault $ordersPort
    $controlProcess=Start-OwnedProcess -Executable $java -Arguments @('-Dfile.encoding=UTF-8','-jar',$jar,'autonomy','run','orders','--state-dir',$stateRoot,'--model',$Model)
    $awaiting=Wait-Incident 'AWAITING_APPROVAL'
    if ($null -ne $awaiting.current.repair) { throw "ASK path repaired before consent" }
    $null=Invoke-Package -Arguments @('status','orders') -Name 'awaiting-status'
    $null=Invoke-Package -Arguments @('pause','orders') -Name 'pause'
    Start-Sleep -Milliseconds 1500
    $mode=Read-SharedJson (Join-Path $stateRoot 'orders/controller/orders.mode.json')
    if ($mode.mode -ne 'PAUSED') { throw "Pause was not preserved" }
    $null=Invoke-Package -Arguments @('resume','orders') -Name 'resume'
    $null=Invoke-Package -Arguments @('approve','orders',$awaiting.current.id,'--confirm') -Name 'human-consent'
    $human=Wait-Incident 'RECOVERED'
    $humanEvidence=Repair-Evidence $human 'HUMAN'
    $null=Assert-Business $ordersPort
    Write-Json 'human-oracle.json' @{ recovered=$true; businessExactMatch=$true; evidence=$humanEvidence; previouslyAwaitingApproval=$true }
    $request=Get-ChildItem -LiteralPath (Join-Path $stateRoot 'orders/inbox') -Filter 'request-*.json' | Select-Object -First 1
    $command=Read-SharedJson $request.FullName
    $receiptDeadline=[DateTime]::UtcNow.AddSeconds(10)
    $receipt=Join-Path $stateRoot ("orders/inbox/receipt-"+$command.id+".json")
    while (-not (Test-Path -LiteralPath $receipt) -and [DateTime]::UtcNow -lt $receiptDeadline) { Start-Sleep -Milliseconds 100 }
    $commandReceipt=Read-SharedJson $receipt
    if (-not $commandReceipt.applied) { throw "Human consent receipt did not confirm consumption" }
    $null=Invoke-Package -Arguments @('command-result','orders',$command.id) -Name 'human-command-result'
    $null=Invoke-Package -Arguments @('stop','orders') -Name 'stop'
    $null=Finish-OwnedProcess $controlProcess 'continuous-controller' 30
    $null=Invoke-Package -Arguments @('status','orders') -Name 'stopped-status'
    $null=Invoke-Package -Arguments @('notifications','orders') -Name 'notifications-disabled'
    $attempts=Read-SharedJson (Join-Path $stateRoot 'orders/execution/attempts/snapshot.json')
    if (@($attempts.attempts).Count -ne 2 -or @($attempts.attempts | Where-Object state -ne 'VERIFIED_SUCCESS').Count -ne 0) { throw "Expected exactly two verified attempts across two distinct incidents" }
    $providerCalls=0; $actualTokens=0
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $stateRoot 'orders/controller') -Filter 'decision-*.json') {
        $decision=Read-SharedJson $file.FullName
        foreach ($usage in $decision.providerUsage) { $providerCalls++; if ($null -ne $usage.usage -and $usage.usage.source -eq 'ACTUAL') { $actualTokens+=$usage.usage.totalTokens } }
    }
    Write-Json 'summary.json' @{ productSmokePassed=$true; evidenceKind='DEVELOPMENT_PRODUCT_SMOKE_NOT_FROZEN_BENCHMARK'; automatic=$automaticEvidence; human=$humanEvidence;
        providerCalls=$providerCalls; actualTokens=$actualTokens; apiCost=$null; feeUnavailable=$true; attempts=2; notificationsSent=0; completedAt=[DateTime]::UtcNow.ToString('o') }
    $success=$true
} catch {
    Write-Json 'failure.json' @{ type=$_.Exception.GetType().Name; message=$_.Exception.Message; at=[DateTime]::UtcNow.ToString('o') }
    throw
} finally {
    if ($null -ne $controlProcess) {
        if (-not $controlProcess.Process.HasExited) {
            try { $null=Invoke-Package -Arguments @('stop','orders') -Name 'cleanup-stop' } catch {}
            if (-not $controlProcess.Process.WaitForExit(30000)) { $controlProcess.Process.Kill($true); $controlProcess.Process.WaitForExit() }
            Write-Json 'interrupted-controller.json' @{ exitCode=$controlProcess.Process.ExitCode; stdout=$controlProcess.Output.GetAwaiter().GetResult(); stderr=$controlProcess.Error.GetAwaiter().GetResult() }
        }
        $controlProcess.Process.Dispose()
    }
    if ($owned) {
        if ($project -notmatch '^clawkit-autonomy-package-[a-f0-9]{12}$' -or $fixture -ne [System.IO.Path]::GetFullPath((Join-Path $packageRoot 'ops-fixtures/layered-autonomy/compose.yaml'))) { throw "Cleanup ownership check failed" }
        $null=Invoke-Owned -Executable $docker -Arguments @('--context',$DockerContext,'compose','-f',$fixture,'-p',$project,'down','--remove-orphans') -Name 'cleanup' -TimeoutSeconds 30
        $remaining=Invoke-Owned -Executable $docker -Arguments @('--context',$DockerContext,'ps','-a','--filter',"label=com.docker.compose.project=$project",'--format','{{.ID}}') -Name 'ownership-after-cleanup'
        if (-not [string]::IsNullOrWhiteSpace($remaining.stdout)) { throw "Owned project has residual containers" }
    }
}
if (-not $success) { throw "Product smoke did not complete" }
Write-Host "Extracted package auto/human/lifecycle smoke passed; artifacts: $outputRoot"
