# Before/after check for the mixin fixes in BUGFIX-REPORT.md, without a Minecraft server.
# Windows PowerShell 5.1 or PowerShell 7. All the work is done by Run.java; this only finds a JDK.
#
#   powershell -ExecutionPolicy Bypass -File tools\mixin-harness\run.ps1
#   powershell -ExecutionPolicy Bypass -File tools\mixin-harness\run.ps1 -Baseline 96cc13c
#   powershell -ExecutionPolicy Bypass -File tools\mixin-harness\run.ps1 -Jdk "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
#   powershell -ExecutionPolicy Bypass -File tools\mixin-harness\run.ps1 -BaselineDir C:\path\to\old\checkout
#
# Needs a JDK 11 or newer (a JRE or Java 8 cannot launch Run.java), git on the PATH (or -BaselineDir), and
# Maven Central reachable on the first run. Exit code 0 means every bug reproduced on the baseline and is
# fixed now.

param(
    [string]$Baseline = "96cc13c",
    [string]$Jdk = "",
    [string]$BaselineDir = ""
)

$ErrorActionPreference = "Stop"
$onWindows = ($env:OS -eq "Windows_NT")
$exeName = if ($onWindows) { "java.exe" } else { "java" }
$javacName = if ($onWindows) { "javac.exe" } else { "javac" }

function Get-JavaExe([string]$jdkHome) { return (Join-Path (Join-Path $jdkHome "bin") $exeName) }

function Get-JavaMajor([string]$javaExe) {
    # "java -version" writes to stderr; capture it without letting PowerShell treat it as an error.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $text = (& $javaExe -version 2>&1 | ForEach-Object { "$_" }) -join "`n"
    $ErrorActionPreference = $previous
    if ($text -match 'version "1\.(\d+)') { return [int]$Matches[1] }
    if ($text -match 'version "(\d+)') { return [int]$Matches[1] }
    return 0
}

$candidates = @()
if ($Jdk -ne "") { $candidates += (Get-JavaExe $Jdk) }
if ($env:JAVA_HOME) { $candidates += (Get-JavaExe $env:JAVA_HOME) }
$onPath = Get-Command java -ErrorAction SilentlyContinue
if ($onPath) { $candidates += $onPath.Source }
$roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Microsoft",
           "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft", "$env:ProgramFiles\Semeru",
           "$env:ProgramFiles\AdoptOpenJDK", "$env:USERPROFILE\.jdks", "$env:USERPROFILE\.gradle\jdks")
if ($env:GRADLE_USER_HOME) { $roots += (Join-Path $env:GRADLE_USER_HOME "jdks") }
foreach ($root in $roots) {
    if (-not (Test-Path $root)) { continue }
    Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        # A JDK home is either the child itself or, in Gradle 8.8 and older toolchain caches, one level below it.
        $jdkHomes = @($_.FullName) + @(Get-ChildItem -Path $_.FullName -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
        foreach ($jh in $jdkHomes) {
            $exe = Get-JavaExe $jh
            if (Test-Path $exe) { $candidates += $exe }
        }
    }
}

$java = $null
foreach ($exe in $candidates) {
    if (-not (Test-Path $exe)) { continue }
    $javac = Join-Path (Split-Path $exe) $javacName
    if (-not (Test-Path $javac)) { continue }        # a JRE has no compiler
    if ((Get-JavaMajor $exe) -ge 11) { $java = $exe; break }
}
if (-not $java) {
    [Console]::Error.WriteLine("ERROR: No JDK 11 or newer found. Pass -Jdk <JDK home folder, the one containing bin\javac.exe>. " +
        "IntelliJ shows the one it builds this project with under Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JVM.")
    exit 2
}

Write-Host "Using $java"
$ErrorActionPreference = "Continue"     # Run.java reports its own errors; never let stderr abort the run
$runJava = Join-Path $PSScriptRoot "Run.java"
if ($BaselineDir -ne "") {
    & $java $runJava --harness $PSScriptRoot --baseline-dir $BaselineDir
} else {
    & $java $runJava --harness $PSScriptRoot --baseline $Baseline
}
exit $LASTEXITCODE
