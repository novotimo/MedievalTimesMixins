# Reproduces the join lockout (register B17) outside the game, then shows it fixed.
#
#   .\tools\run-profile-cache-race.ps1
#
# "unsafe" is vanilla 1.12.2 behaviour and should fail with a NullPointerException
# inside LinkedList$ListItr.next - the same exception, in the same place, as the
# production crash. "safe" wraps each call in synchronized (cache), which is the
# monitor register B17 takes, and should report no failures at all.
#
# Needs 'gradle build' to have run at least once, for build/rfg/recompiled_minecraft.

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$cacheRoot = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1'
$jdkRoot = Join-Path $env:USERPROFILE '.gradle\jdks'

function Find-OneJar([string]$pattern) {
    $jar = Get-ChildItem -Path $cacheRoot -Recurse -Filter $pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if ($null -eq $jar) { throw "Could not find $pattern under $cacheRoot - run 'gradle build' first." }
    return $jar.FullName
}

$javac = Get-ChildItem -Path $jdkRoot -Recurse -Filter 'javac.exe' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '8' } | Select-Object -First 1
if ($null -eq $javac) { throw "No Java 8 javac under $jdkRoot - run 'gradle build' so the toolchain is downloaded." }
$java = Join-Path (Split-Path -Parent $javac.FullName) 'java.exe'

$minecraft = Join-Path $project 'build\rfg\recompiled_minecraft-1.12.2.jar'
if (-not (Test-Path $minecraft)) { throw "Missing $minecraft - run 'gradle build' first." }

$parts = @(
    $minecraft,
    (Find-OneJar 'authlib-*.jar'),
    (Find-OneJar 'guava-2*.jar'),
    (Find-OneJar 'gson-2*.jar'),
    (Find-OneJar 'commons-io-2*.jar')
)
$cp = $parts -join ';'

$out = Join-Path $project 'build\tools'
if (-not (Test-Path $out)) { New-Item -ItemType Directory -Path $out | Out-Null }

Write-Host 'Compiling the harness...'
& $javac.FullName -cp $cp -d $out (Join-Path $PSScriptRoot 'ProfileCacheRace.java')
if ($LASTEXITCODE -ne 0) { throw 'Harness did not compile.' }

$full = "$out;$cp"

Write-Host ''
Write-Host '================ BEFORE: vanilla 1.12.2 ================'
& $java -cp $full ProfileCacheRace unsafe

Write-Host ''
Write-Host '================ AFTER: what register B17 does ================'
& $java -cp $full ProfileCacheRace safe
