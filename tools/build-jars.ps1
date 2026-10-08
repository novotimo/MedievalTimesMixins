# Builds the two jars BUGFIX-REPORT.md tests with: the baseline (sources as of -Baseline) and the fixed
# jar (the current sources). Windows PowerShell 5.1 or PowerShell 7, run from anywhere.
#
#   powershell -ExecutionPolicy Bypass -File tools\build-jars.ps1
#   powershell -ExecutionPolicy Bypass -File tools\build-jars.ps1 -Jdk "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
#
# Writes mtmixins-BASELINE.jar and mtmixins-FIXED.jar into the folder that contains the repository (or
# -OutDir). Uses gradlew.bat if the repository has one, otherwise `gradle` from the PATH. Gradle 9 needs a
# JDK 17 or newer to run; pass -Jdk if JAVA_HOME points at something older.
#
# src\ is overwritten with the baseline sources for the first build and restored afterwards (also when the
# build fails), so it must have no uncommitted changes. Nothing outside src\ and build\ is touched.

param(
    [string]$Baseline = "96cc13c",
    [string]$OutDir = "",
    [string]$Jdk = ""
)

$ErrorActionPreference = "Stop"
$onWindows = ($env:OS -eq "Windows_NT")
$repo = Split-Path -Parent $PSScriptRoot
if ($OutDir -eq "") { $OutDir = Split-Path -Parent $repo }
$libs = Join-Path (Join-Path $repo "build") "libs"
$probe = "com/novotimo/mtmixins/mixin/lycanites/MixinMaterialSpawnLocation"

# Runs a native command with its output on the console; only the exit code decides success.
function Invoke-Native([string]$what, [string]$exe, [string[]]$argv) {
    $ErrorActionPreference = "Continue"     # Gradle and git write progress to stderr; that is not a failure
    & $exe @argv | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)." }
}

function Get-GitOutput([string[]]$argv) {
    $ErrorActionPreference = "Continue"
    $out = (& git -C $repo @argv 2>&1 | ForEach-Object { "$_" }) -join "`n"
    return @{ Code = $LASTEXITCODE; Text = $out }
}

function Get-JarEntries([string]$jar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try { return @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
}

function Build-Jar([string]$label, [string]$destination) {
    if (Test-Path $libs) { Remove-Item -Recurse -Force $libs }
    Write-Host "=== Building the $label jar"
    Invoke-Native "The $label build" $script:gradle @("-p", $repo, "clean", "build")
    $jars = @(Get-ChildItem -Path $libs -Filter "mtmixins-*.jar" -ErrorAction SilentlyContinue |
              Where-Object { $_.Name -notmatch '-(dev|sources|javadoc)\.jar$' })
    if ($jars.Count -ne 1) {
        throw "Expected exactly one mod jar in $libs after the $label build, found: $(($jars | ForEach-Object { $_.Name }) -join ', ')"
    }
    Copy-Item -Force $jars[0].FullName $destination
    Write-Host "    $($jars[0].Name) -> $destination"
}

$failed = $false
$srcReplaced = $false
try {
    if (-not (Test-Path $OutDir -PathType Container)) { throw "Output folder $OutDir does not exist." }
    $OutDir = (Resolve-Path $OutDir).Path
    if ($Jdk -ne "") { $env:JAVA_HOME = $Jdk }

    if ($onWindows -and (Test-Path (Join-Path $repo "gradlew.bat"))) { $gradle = Join-Path $repo "gradlew.bat" }
    elseif (-not $onWindows -and (Test-Path (Join-Path $repo "gradlew"))) { $gradle = Join-Path $repo "gradlew" }
    elseif (Get-Command gradle -ErrorAction SilentlyContinue) { $gradle = "gradle" }
    else {
        throw "This repository has no gradlew.bat and there is no gradle on the PATH. Either install Gradle 9.7 and run " +
              "'gradle wrapper --gradle-version 9.7.0' once in the repository, or build in IntelliJ by hand as described " +
              "in BUGFIX-REPORT.md section 0.1."
    }

    $status = Get-GitOutput @("status", "--porcelain", "--", "src")
    if ($status.Code -ne 0) { throw "git status failed: $($status.Text)" }
    if ($status.Text.Trim() -ne "") {
        throw "src has uncommitted changes, which this script would overwrite. Commit or 'git stash' them first:`n$($status.Text)"
    }
    $rev = Get-GitOutput @("rev-parse", "--verify", "--quiet", "$Baseline^{commit}")
    if ($rev.Code -ne 0) { throw "Baseline revision $Baseline not found. Run 'git fetch origin' first." }
    $head = (Get-GitOutput @("rev-parse", "--short", "HEAD")).Text.Trim()
    $branch = (Get-GitOutput @("rev-parse", "--abbrev-ref", "HEAD")).Text.Trim()
    Write-Host "Repository $repo, on $branch ($head). Baseline $Baseline. Gradle: $gradle"

    $baselineJar = Join-Path $OutDir "mtmixins-BASELINE.jar"
    $fixedJar = Join-Path $OutDir "mtmixins-FIXED.jar"
    foreach ($jar in @($baselineJar, $fixedJar)) { if (Test-Path $jar) { Remove-Item -Force $jar } }

    $srcReplaced = $true
    Invoke-Native "git restore --source $Baseline" "git" @("-C", $repo, "restore", "--source", $Baseline, "--worktree", "--", "src")
    Build-Jar "baseline ($Baseline)" $baselineJar
    Invoke-Native "git restore" "git" @("-C", $repo, "restore", "--worktree", "--", "src")
    $srcReplaced = $false
    Build-Jar "fixed ($head)" $fixedJar

    # Proof that the two jars really are different code, not the same build copied twice.
    $baselineHasProbe = (Get-JarEntries $baselineJar) -contains "$probe.class"
    $fixedHasProbe = (Get-JarEntries $fixedJar) -contains "$probe.class"
    $probeAtBaseline = (Get-GitOutput @("cat-file", "-e", "${Baseline}:src/main/java/$probe.java")).Code -eq 0
    if ($baselineHasProbe -ne $probeAtBaseline) { throw "The baseline jar does not match $Baseline ($probe.class present: $baselineHasProbe)." }
    if (-not $fixedHasProbe) { throw "The fixed jar has no $probe.class; it was not built from the current sources." }
    $h1 = (Get-FileHash -Algorithm SHA256 $baselineJar).Hash
    $h2 = (Get-FileHash -Algorithm SHA256 $fixedJar).Hash
    if ($h1 -eq $h2) { throw "Both jars are identical." }

    $after = Get-GitOutput @("status", "--porcelain", "--", "src")
    if ($after.Text.Trim() -ne "") { throw "src is not clean after the builds:`n$($after.Text)" }

    Write-Host ""
    Write-Host "mtmixins-BASELINE.jar  SHA-256 $h1  ($Baseline)"
    Write-Host "mtmixins-FIXED.jar     SHA-256 $h2  ($branch $head)"
    Write-Host "Both are in $OutDir. src is back to the current sources."
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    $failed = $true
} finally {
    if ($srcReplaced) {
        & git -C $repo restore --worktree -- src
        Write-Host "src restored to the current sources."
    }
}
if ($failed) { exit 1 }
exit 0
