[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Platform = 'android-36',
    [string]$BuildToolsVersion = '36.0.0',
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\build-repro')
)

$ErrorActionPreference = 'Stop'
if (-not $SdkRoot) {
    $SdkRoot = $env:ANDROID_SDK_ROOT
}
if (-not $SdkRoot -and $env:LOCALAPPDATA) {
    $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not $SdkRoot -or -not (Test-Path -LiteralPath $SdkRoot -PathType Container)) {
    throw 'Set -SdkRoot to your local Android SDK directory.'
}

$platformJar = Join-Path $SdkRoot "platforms\$Platform\android.jar"
$d8 = Join-Path $SdkRoot "build-tools\$BuildToolsVersion\d8.bat"
Get-Item -LiteralPath $platformJar, $d8 | Out-Null
$source = Join-Path $PSScriptRoot '..\src\HzLock.java'
$javac = (Get-Command javac -ErrorAction Stop).Source

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$resolvedOutput = (Resolve-Path -LiteralPath $OutputDirectory).Path
$classes = Join-Path $resolvedOutput 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null

& $javac -source 11 -target 11 -Xlint:-options -encoding UTF-8 -classpath $platformJar -d $classes $source
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE."
}

$classFile = Join-Path $classes 'HzLock.class'
& $d8 --min-api 34 --output $resolvedOutput $classFile
if ($LASTEXITCODE -ne 0) {
    throw "D8 failed with exit code $LASTEXITCODE."
}

$result = Join-Path $resolvedOutput 'hzlock.dex'
Move-Item -LiteralPath (Join-Path $resolvedOutput 'classes.dex') -Destination $result -Force
Get-Item -LiteralPath $result
