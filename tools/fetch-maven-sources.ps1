<#
.SYNOPSIS
  把本项目用到的第三方库 -sources.jar 拉到本地并解包，供人工 / IDE 直接阅读源码。

.DESCRIPTION
  按坐标（group:name:version）逐个直连 Maven 仓库下载 <name>-<version>-sources.jar：
    - group 以 androidx 开头 -> Google Maven (https://dl.google.com/dl/android/maven2)
    - 其余（miuix / kotlinx / kotlin）-> Maven Central (https://repo1.maven.org/maven2)
  下载物与解包目录都落在 $Dest（默认 %USERPROFILE%\.gradle\sources），并生成 index.tsv。
  本脚本不触碰 Gradle 构建、不修改任何依赖，对 APK 产物零影响。

.PARAMETER Modules
  可选，覆盖内置清单。格式 group:name:version，可传多个。
.PARAMETER Dest
  输出根目录，默认 %USERPROFILE%\.gradle\sources。
.PARAMETER Refresh
  已存在则重新下载、重新解包。
.PARAMETER TimeoutSec
  单次 HTTP 超时，默认 30。

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fetch-maven-sources.ps1
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\fetch-maven-sources.ps1 -Modules androidx.compose.material3:material3:1.5.0-alpha29
#>
[CmdletBinding()]
param(
    [string[]]$Modules,
    [string]$Dest = (Join-Path $env:USERPROFILE '.gradle\sources'),
    [switch]$Refresh,
    [int]$TimeoutSec = 30
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$GoogleRepo = 'https://dl.google.com/dl/android/maven2'
$CentralRepo = 'https://repo1.maven.org/maven2'

# 本项目当前锁定的坐标（升级版本后改这里，或直接用 -Modules 覆盖）
$DefaultModules = @(
    'androidx.compose.material3:material3:1.5.0-alpha29',
    'androidx.compose.material3:material3-android:1.5.0-alpha29',
    'androidx.compose.foundation:foundation:1.13.0-alpha01',
    'androidx.compose.foundation:foundation-android:1.13.0-alpha01',
    'androidx.compose.foundation:foundation-layout:1.13.0-alpha01',
    'androidx.compose.animation:animation:1.13.0-alpha01',
    'androidx.compose.animation:animation-android:1.13.0-alpha01',
    'androidx.compose.animation:animation-core:1.13.0-alpha01',
    'androidx.compose.animation:animation-core-android:1.13.0-alpha01',
    'androidx.compose.runtime:runtime:1.13.0-alpha01',
    'androidx.compose.runtime:runtime-android:1.13.0-alpha01',
    'androidx.compose.runtime:runtime-saveable:1.13.0-alpha01',
    'androidx.compose.ui:ui:1.13.0-alpha01',
    'androidx.compose.ui:ui-android:1.13.0-alpha01',
    'androidx.compose.ui:ui-graphics:1.13.0-alpha01',
    'androidx.compose.ui:ui-text:1.13.0-alpha01',
    'androidx.compose.ui:ui-geometry:1.13.0-alpha01',
    'androidx.compose.ui:ui-unit:1.13.0-alpha01',
    'androidx.navigationevent:navigationevent:1.1.2',
    'androidx.navigationevent:navigationevent-android:1.1.2',
    'androidx.navigationevent:navigationevent-compose:1.1.2',
    'androidx.navigationevent:navigationevent-compose-android:1.1.2',
    'top.yukonga.miuix.kmp:miuix-nav-android:0.9.4',
    'top.yukonga.miuix.kmp:miuix-squircle:0.9.4',
    'top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4',
    'top.yukonga.miuix.kmp:miuix-shader:0.9.4',
    'top.yukonga.miuix.kmp:miuix-shader-android:0.9.4',
    'org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0',
    'org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0',
    'org.jetbrains.kotlin:kotlin-stdlib:2.4.20'
)

if (-not $Modules -or $Modules.Count -eq 0) { $Modules = $DefaultModules }

New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$index = Join-Path $Dest 'index.tsv'
if ((-not (Test-Path $index)) -or $Refresh) {
    "status`tcoordinates`tjar`texpandedDir`tbytes" | Set-Content -Encoding UTF8 $index
}

$ok = 0
$fail = 0
$failed = @()

foreach ($gav in $Modules) {
    $parts = $gav.Split(':')
    if ($parts.Count -ne 3) {
        "SKIP  $gav (expect group:name:version)"
        continue
    }
    $group = $parts[0]
    $name = $parts[1]
    $version = $parts[2]

    $rel = ($group -replace '\.', '/') + '/' + $name + '/' + $version + '/' + $name + '-' + $version + '-sources.jar'
    $repo = if ($group.StartsWith('androidx')) { $GoogleRepo } else { $CentralRepo }
    $url = $repo + '/' + $rel

    $folder = Join-Path $Dest ($group + '_' + $name + '_' + $version)
    $jar = Join-Path $folder ($name + '-' + $version + '-sources.jar')
    $expand = Join-Path $folder ($name + '-' + $version + '-sources')

    $status = 'cached'
    if ((-not (Test-Path $jar)) -or $Refresh) {
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
        try {
            Invoke-WebRequest -Uri $url -OutFile $jar -TimeoutSec $TimeoutSec -UseBasicParsing
            $status = 'ok'
        } catch {
            if (Test-Path $jar) { Remove-Item $jar -Force -ErrorAction SilentlyContinue }
            $code = 'n/a'
            if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                $code = $_.Exception.Response.StatusCode.value__
            }
            "FAIL  $gav  HTTP=$code  $($_.Exception.Message)"
            $fail++
            $failed += ($gav + ' (HTTP ' + $code + ')')
            ("fail`t" + $gav + "`t" + $url + "`t`t" + $code) | Add-Content -Encoding UTF8 $index
            continue
        }
    }

    if ($status -eq 'ok' -or (-not (Test-Path $expand))) {
        if (Test-Path $expand) { Remove-Item $expand -Recurse -Force }
        try {
            [System.IO.Compression.ZipFile]::ExtractToDirectory($jar, $expand)
        } catch {
            "WARN  $gav extract failed: $($_.Exception.Message)"
        }
    }

    $ok++
    $size = (Get-Item $jar).Length
    "{0}  {1}  ({2} bytes)" -f $status.ToUpper(), $gav, $size
    ("{0}`t{1}`t{2}`t{3}`t{4}" -f $status, $gav, $jar, $expand, $size) | Add-Content -Encoding UTF8 $index
}

'----'
"OK {0} / {1}, FAIL {2}" -f $ok, $Modules.Count, $fail
if ($failed.Count -gt 0) { 'Failed: ' + ($failed -join '; ') }
"Index: $index"
