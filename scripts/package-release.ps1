<#
.SYNOPSIS
    Clawkit release packaging — builds JAR, Windows ZIP, and SHA-256 checksums.
    Called by both CI (release.yml) and local verification. Single source of truth.
.DESCRIPTION
    Accepts -Version and -OutputDir. Copies the shaded JAR, builds a Windows
    package directory (clawkit.jar + clawkit.cmd + README.md), creates the final
    ZIP, unpacks it to a temp dir, smoke-tests the unpacked artifacts, and
    generates SHA256SUMS.txt.  Exits non-zero on any failure.
.PARAMETER Version
    Release version string, e.g. "0.1.0". Required.
.PARAMETER OutputDir
    Directory to write release artifacts into. Default: "dist".
.EXAMPLE
    .\scripts\package-release.ps1 -Version "0.1.0"
    .\scripts\package-release.ps1 -Version "0.1.0" -OutputDir "release-out"
#>

param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$OutputDir = "dist"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

# ═══════════════════════════════════════════════════════════════
# Paths
# ═══════════════════════════════════════════════════════════════
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$jarName  = "clawkit-$Version.jar"
$zipName  = "clawkit-$Version-windows.zip"
$winDir   = Join-Path $OutputDir "clawkit-$Version-windows"
$srcJar   = Join-Path $repoRoot "clawkit-cli/target/clawkit-cli-$Version.jar"

if (-not (Test-Path $srcJar)) {
    Write-Error "Shaded JAR not found: $srcJar. Run 'mvn -pl clawkit-cli -am package' first."
    exit 1
}

Write-Host "=== Packaging clawkit $Version ==="

# ═══════════════════════════════════════════════════════════════
# 1. Copy JAR
# ═══════════════════════════════════════════════════════════════
New-Item -ItemType Directory -Force $OutputDir | Out-Null
$OutputDir = (Resolve-Path -LiteralPath $OutputDir).Path
$winDir = Join-Path $OutputDir "clawkit-$Version-windows"
if (Test-Path -LiteralPath $winDir) { throw "Package directory already exists; choose a fresh OutputDir to avoid mixing old files." }
Copy-Item $srcJar (Join-Path $OutputDir $jarName)

# ═══════════════════════════════════════════════════════════════
# 2. Build Windows package directory
# ═══════════════════════════════════════════════════════════════
New-Item -ItemType Directory -Force $winDir | Out-Null
Copy-Item (Join-Path $OutputDir $jarName) (Join-Path $winDir "clawkit.jar")
Copy-Item (Join-Path $repoRoot "clawkit.cmd") (Join-Path $winDir "clawkit.cmd")
Copy-Item (Join-Path $repoRoot "clawkit.sh") (Join-Path $winDir "clawkit.sh")
Copy-Item (Join-Path $repoRoot "docs/managed-operations.md") (Join-Path $winDir "README.md")
Copy-Item (Join-Path $repoRoot "TODO.md") (Join-Path $winDir "TODO.md")
$packageReadme=Join-Path $winDir 'README.md'
$readmeText=[System.IO.File]::ReadAllText($packageReadme) -replace '\]\(\.\./','](./'
[System.IO.File]::WriteAllText($packageReadme,$readmeText,[System.Text.UTF8Encoding]::new($false))
$docsDir=Join-Path $winDir 'docs'
New-Item -ItemType Directory -Force $docsDir | Out-Null
foreach($name in @('managed-operations.md','layered-autonomy-implementation-plan.md','autonomy-evaluation.md')) {
    Copy-Item (Join-Path $repoRoot ('docs/'+$name)) (Join-Path $docsDir $name)
}
$benchmarkDir=Join-Path $winDir 'benchmarks'
New-Item -ItemType Directory -Force $benchmarkDir | Out-Null
Copy-Item (Join-Path $repoRoot 'benchmarks/layered-autonomy-v1.json') $benchmarkDir
Copy-Item (Join-Path $repoRoot 'benchmarks/intelligence-autonomy-v1.json') $benchmarkDir
Copy-Item (Join-Path $repoRoot 'benchmarks/intelligence-autonomy-v2.json') $benchmarkDir
Copy-Item (Join-Path $repoRoot 'benchmarks/evidence') $benchmarkDir -Recurse
$examplesDir=Join-Path $winDir 'examples/autonomy'
New-Item -ItemType Directory -Force $examplesDir | Out-Null
Copy-Item (Join-Path $repoRoot 'examples/autonomy/*.json') $examplesDir
Copy-Item (Join-Path $repoRoot 'examples/autonomy/*.yaml') $examplesDir
$fixtureDir = Join-Path $winDir "ops-fixtures/layered-autonomy"
New-Item -ItemType Directory -Force $fixtureDir | Out-Null
Copy-Item (Join-Path $repoRoot "ops-fixtures/layered-autonomy/compose.yaml") (Join-Path $fixtureDir "compose.yaml")
Copy-Item (Join-Path $repoRoot "ops-fixtures/layered-autonomy/server.py") (Join-Path $fixtureDir "server.py")
foreach($fixtureName in @('diagnostic-autonomy','intelligence-autonomy')) {
    $extraFixture=Join-Path $winDir ('ops-fixtures/'+$fixtureName)
    New-Item -ItemType Directory -Force $extraFixture | Out-Null
    Copy-Item (Join-Path $repoRoot ('ops-fixtures/'+$fixtureName+'/compose.yaml')) $extraFixture
    Copy-Item (Join-Path $repoRoot ('ops-fixtures/'+$fixtureName+'/server.py')) $extraFixture
}
$packagedFiles=[ordered]@{}
Get-ChildItem -LiteralPath $winDir -Recurse -File | ForEach-Object {
    $name=$_.FullName.Substring($winDir.Length+1).Replace('\','/')
    $packagedFiles[$name]=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
}
$buildManifest=@{ version=$Version; packagedAt=[DateTime]::UtcNow.ToString('o'); gitHead=(& git -C $repoRoot rev-parse HEAD);
    trackedWorktreeDirty=(@(& git -C $repoRoot status --porcelain --untracked-files=no).Count -gt 0); files=$packagedFiles }
[System.IO.File]::WriteAllText((Join-Path $winDir 'build-manifest.json'),($buildManifest | ConvertTo-Json -Depth 10),[System.Text.UTF8Encoding]::new($false))

# ═══════════════════════════════════════════════════════════════
# 3. Smoke JAR (before ZIP, catches shading problems early)
# ═══════════════════════════════════════════════════════════════
$jarPath = Join-Path $OutputDir $jarName

Write-Host "Smoke: java -jar ... --help"
$helpOutput = & java -jar $jarPath --help 2>&1
if ($LASTEXITCODE -ne 0) { throw "JAR --help failed: $helpOutput" }

Write-Host "Smoke: java -jar ... --version"
$jarVersion = & java -jar $jarPath --version 2>&1
$expectedJarVersion = "clawkit $Version"
if ($jarVersion -ne $expectedJarVersion) { throw "JAR --version mismatch: got '$jarVersion', expected '$expectedJarVersion'" }
$autonomyHelp = & java -jar $jarPath autonomy --help 2>&1
if ($LASTEXITCODE -ne 0 -or "$autonomyHelp" -notmatch "diagnose" -or "$autonomyHelp" -notmatch "knowledge-search" -or "$autonomyHelp" -notmatch "alert-listen") { throw "JAR autonomy --help lacks intelligence commands" }

# ═══════════════════════════════════════════════════════════════
# 4. Create ZIP
# ═══════════════════════════════════════════════════════════════
$zipPath = Join-Path $OutputDir $zipName
Compress-Archive -Path "$winDir/*" -DestinationPath $zipPath -Force

# ═══════════════════════════════════════════════════════════════
# 5. Unpack and verify from extracted directory
# ═══════════════════════════════════════════════════════════════
$unpackDir = Join-Path $OutputDir ("_unpack-verify-" + [guid]::NewGuid().ToString("N"))
if (Test-Path -LiteralPath $unpackDir) { throw "Unexpected unpack directory collision" }
Expand-Archive -Path $zipPath -DestinationPath $unpackDir

Write-Host "Smoke: unpacked clawkit.cmd --version"
$windowsVersion = & (Join-Path $unpackDir "clawkit.cmd") --version 2>&1
if ($windowsVersion -ne $expectedJarVersion) { throw "ZIP --version mismatch: got '$windowsVersion', expected '$expectedJarVersion'" }
$unpackedHelp = & (Join-Path $unpackDir "clawkit.cmd") autonomy --help 2>&1
if ($LASTEXITCODE -ne 0 -or "$unpackedHelp" -notmatch "register") { throw "ZIP autonomy --help failed" }
$emptyStatus = & (Join-Path $unpackDir "clawkit.cmd") autonomy status --state-dir (Join-Path $unpackDir "empty-state") 2>&1
if ($LASTEXITCODE -ne 0) { throw "ZIP empty local status failed" }

# Verify both files exist and have content
$unpackedJar = Join-Path $unpackDir "clawkit.jar"
$unpackedCmd = Join-Path $unpackDir "clawkit.cmd"
$unpackedReadme = Join-Path $unpackDir "README.md"
foreach ($f in @($unpackedJar, $unpackedCmd, $unpackedReadme, (Join-Path $unpackDir "clawkit.sh"),
    (Join-Path $unpackDir "ops-fixtures/layered-autonomy/compose.yaml"), (Join-Path $unpackDir "ops-fixtures/layered-autonomy/server.py"))) {
    if (-not (Test-Path $f)) { throw "Missing in ZIP: $(Split-Path $f -Leaf)" }
    if ((Get-Item $f).Length -eq 0) { throw "Zero-byte file in ZIP: $(Split-Path $f -Leaf)" }
}
$unpackedManifest=Get-Content -LiteralPath (Join-Path $unpackDir 'build-manifest.json') -Raw | ConvertFrom-Json
foreach($entry in $unpackedManifest.files.PSObject.Properties) {
    $file=Join-Path $unpackDir $entry.Name
    if((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.Value) { throw "Extracted package content hash mismatch: $($entry.Name)" }
}

# Clean up unpack dir
$resolvedUnpack = (Resolve-Path -LiteralPath $unpackDir).Path
$outputPrefix = $OutputDir.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $resolvedUnpack.StartsWith($outputPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
    (Split-Path $resolvedUnpack -Leaf) -notmatch '^_unpack-verify-[a-f0-9]{32}$') { throw "Unpack cleanup ownership check failed" }
Remove-Item -LiteralPath $resolvedUnpack -Recurse -Force

# ═══════════════════════════════════════════════════════════════
# 6. Generate SHA-256 checksums
# ═══════════════════════════════════════════════════════════════
$checksumPath = Join-Path $OutputDir "SHA256SUMS.txt"
$jarHash = (Get-FileHash $jarPath -Algorithm SHA256).Hash.ToLower()
$zipHash = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()
@(
    "$jarHash  $jarName"
    "$zipHash  $zipName"
) | Set-Content $checksumPath -Encoding ASCII

# ═══════════════════════════════════════════════════════════════
# 7. Verify checksum file self-consistency
# ═══════════════════════════════════════════════════════════════
Write-Host "Verify: SHA256SUMS.txt"
$verified = 0
Get-Content $checksumPath | ForEach-Object {
    $parts = $_ -split '\s+', 2
    if ($parts.Count -eq 2) {
        $expectedHash = $parts[0]
        $fileName = $parts[1]
        $actualHash = (Get-FileHash (Join-Path $OutputDir $fileName) -Algorithm SHA256).Hash.ToLower()
        if ($actualHash -ne $expectedHash) {
            throw "Checksum mismatch for $fileName : expected $expectedHash, got $actualHash"
        }
        $verified++
    }
}
if ($verified -ne 2) { throw "SHA256SUMS.txt should cover 2 files, covered $verified" }

# ═══════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════
Write-Host ""
Write-Host "=== Release package complete ==="
Write-Host "JAR : $(Join-Path $OutputDir $jarName)"
Write-Host "ZIP : $zipPath"
Write-Host "SHA : $checksumPath"
Get-Content $checksumPath
