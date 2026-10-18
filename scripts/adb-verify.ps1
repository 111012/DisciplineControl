# 一键真机验收：装 debug 包 -> 跑完整场景 -> 输出 PASS/FAIL -> 清理并（默认）装回 release 包
# 前提：平板已连 USB 且已授权、当前没人在用（会拉起/切走前台应用、会关屏亮屏）
# 用法：
#   pwsh -ExecutionPolicy Bypass -File scripts/adb-verify.ps1
#   pwsh -ExecutionPolicy Bypass -File scripts/adb-verify.ps1 -SkipTimingTestCase   # 跳过 3 分钟计时用例
#   pwsh -ExecutionPolicy Bypass -File scripts/adb-verify.ps1 -KeepDebug            # 结束后不装回 release
param(
    [string]$Target = 'com.tencent.tmgp.sgame',
    [switch]$SkipTimingTestCase,
    [switch]$KeepDebug
)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.toolchain\android-sdk\platform-tools\adb.exe'
$env:TMP = Join-Path $root '.toolchain\tmp'
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null

$PKG = 'com.discipline.control'
$A11Y = "$PKG/$PKG.service.GuardAccessibilityService"
$results = New-Object System.Collections.ArrayList

function Report([string]$name, [bool]$ok, [string]$detail = '') {
    [void]$results.Add([pscustomobject]@{ Test = $name; Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Detail = $detail })
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("  [{0}] {1} {2}" -f $(if ($ok) { 'PASS' } else { 'FAIL' }), $name, $detail) -ForegroundColor $color
}
function Dbg([string[]]$extra) { & $adb shell am broadcast -a "$PKG.DEBUG" -p $PKG @extra 2>&1 | Out-Null }
function Rule($startHour, $endHour, $opens, $sessionMin, $graceMin, $gapMin) {
    Dbg @('--es', 'cmd', 'rule', '--es', 'pkg', $Target,
        '--ei', 'startHour', "$startHour", '--ei', 'endHour', "$endHour",
        '--ei', 'opens', "$opens", '--ei', 'sessionMin', "$sessionMin",
        '--ei', 'graceMin', "$graceMin", '--ei', 'gapMin', "$gapMin")
}
function Usage($opens) {
    Dbg @('--es', 'cmd', 'set-usage', '--es', 'pkg', $Target, '--ei', 'opens', "$opens")
}
function Launch() { & $adb shell monkey -p $Target -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null }
function TopActivity() {
    $line = (& $adb shell dumpsys activity activities 2>&1 | Select-String 'topResumedActivity' | Select-Object -First 1) -join ' '
    return $line
}
function LogHas([string]$pattern) {
    $out = & $adb logcat -d -v brief 2>&1
    $hit = ($out | Select-String $pattern | Select-Object -First 1) -join ''
    return [string]::IsNullOrWhiteSpace($hit) -eq $false
}
function ClearLog() { & $adb logcat -c 2>&1 | Out-Null }
function Home() { & $adb shell input keyevent KEYCODE_HOME 2>&1 | Out-Null }
function Grant() {
    & $adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
    & $adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
    & $adb shell dumpsys deviceidle whitelist "+$PKG" 2>&1 | Out-Null
    & $adb shell cmd package set-home-activity "$PKG/.ui.HomeActivity" 2>&1 | Out-Null
    & $adb shell settings put secure enabled_accessibility_services $A11Y 2>&1 | Out-Null
    & $adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
}
function LatestApk([string]$kind) {
    return (Get-ChildItem (Join-Path $root "dist\*-$kind.apk") | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}

Write-Host '=== 0. 设备检查 ===' -ForegroundColor Cyan
& $adb start-server | Out-Null
if (-not ((& $adb devices) | Where-Object { $_ -match "`tdevice$" })) {
    Write-Host '没有已授权设备，退出' -ForegroundColor Red
    exit 1
}
$targetInstalled = (& $adb shell pm list packages $Target | Out-String).Trim()
if (-not $targetInstalled) { Write-Host "目标应用 $Target 未安装，退出" -ForegroundColor Red; exit 1 }

Write-Host '=== 1. 安装 debug 包 + 授权 ===' -ForegroundColor Cyan
& $adb install -r -g (LatestApk 'debug') 2>&1 | Select-Object -Last 1
Grant
Start-Sleep -Seconds 4
Report '无障碍服务已开启' (($((( & $adb shell settings get secure enabled_accessibility_services) | Out-String).Trim())) -match [regex]::Escape($PKG)) ''
Report '守护服务在运行' (@(& $adb shell pidof $PKG).Count -gt 0) ''

Write-Host '=== 2. 时段外拦截 ===' -ForegroundColor Cyan
Dbg @('--es', 'cmd', 'reset-day')
Rule 3 4 3 60 20 5
Start-Sleep -Seconds 2
ClearLog
Launch
Start-Sleep -Seconds 4
$top = TopActivity
$blocked = ($top -notmatch [regex]::Escape($Target)) -and (LogHas 'block pkg=.*reason=NOT_IN_WINDOW')
Report '时段外启动被弹回桌面' $blocked $top.Trim()

Write-Host '=== 3. 次数用完拦截 ===' -ForegroundColor Cyan
Dbg @('--es', 'cmd', 'reset-day')
Rule 0 24 3 60 20 5
Usage 3
Start-Sleep -Seconds 2
Home; Start-Sleep -Seconds 2
ClearLog
Launch
Start-Sleep -Seconds 4
$top = TopActivity
Report '第 4 次启动被拦（NO_OPENS_LEFT）' (($top -notmatch [regex]::Escape($Target)) -and (LogHas 'reason=NO_OPENS_LEFT')) $top.Trim()

if (-not $SkipTimingTestCase) {
    Write-Host '=== 4. 计时提醒 + 强制退出（约 3 分钟）===' -ForegroundColor Cyan
    Dbg @('--es', 'cmd', 'reset-day')
    Rule 0 24 3 1 1 0
    Start-Sleep -Seconds 2
    Home; Start-Sleep -Seconds 2
    ClearLog
    Launch
    Start-Sleep -Seconds 75
    $warn = LogHas 'warn pkg='
    Report '第 1 分钟触发提醒' $warn ''
    Start-Sleep -Seconds 65
    $finish = LogHas 'finish pkg=.*reason=DAY_FINISHED'
    $top = TopActivity
    Report '第 2 分钟强制退出并回桌面' ($finish -and ($top -notmatch [regex]::Escape($Target))) $top.Trim()
}

Write-Host '=== 5. 待生效修改自动生效（20 秒版）===' -ForegroundColor Cyan
Dbg @('--es', 'cmd', 'reset-day')
Rule 0 24 1 60 20 5
Start-Sleep -Seconds 2
ClearLog
Dbg @('--es', 'cmd', 'pending-demo', '--es', 'pkg', $Target)
Start-Sleep -Seconds 32
Dbg @('--es', 'cmd', 'status')
Start-Sleep -Seconds 2
$log = (& $adb logcat -d -v brief 2>&1) -join "`n"
Report '待生效修改已自动应用' (($log -match '"dailyOpenLimit":6') -and ($log -match 'pending=\s*\r?\n')) ''

Write-Host '=== 6. 关屏/亮屏后会话续接 ===' -ForegroundColor Cyan
Dbg @('--es', 'cmd', 'reset-day')
Rule 0 24 3 60 5 5
Start-Sleep -Seconds 2
ClearLog
Launch
Start-Sleep -Seconds 7
& $adb shell input keyevent 26 2>&1 | Out-Null
Start-Sleep -Seconds 3
& $adb shell input keyevent 224 2>&1 | Out-Null
& $adb shell wm dismiss-keyguard 2>&1 | Out-Null
Start-Sleep -Seconds 7
Report '亮屏后会话续接（resuming=true）' (LogHas 'session start pkg=.*resuming=true') ''

Write-Host '=== 7. 清理 + 恢复 ===' -ForegroundColor Cyan
Dbg @('--es', 'cmd', 'remove', '--es', 'pkg', $Target)
Dbg @('--es', 'cmd', 'reset-day')
Dbg @('--es', 'cmd', 'config', '--ei', 'remindStyle', '0', '--ei', 'relaxDelayHours', '24', '--ei', 'resetHour', '0')
Home
Start-Sleep -Seconds 2
if (-not $KeepDebug) {
    $rel = LatestApk 'release'
    if ($rel) {
        & $adb install -r $rel 2>&1 | Select-Object -Last 1
        Start-Sleep -Seconds 3
        & $adb shell cmd package set-home-activity "$PKG/.ui.HomeActivity" 2>&1 | Out-Null
        & $adb shell settings put secure enabled_accessibility_services $A11Y 2>&1 | Out-Null
        & $adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null
        Write-Host "  已装回 release 包：$(Split-Path $rel -Leaf)"
    }
}

Write-Host ''
Write-Host '================ 验收结果 ================' -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-String | Write-Host
$failed = @($results | Where-Object { $_.Result -eq 'FAIL' }).Count
Write-Host ("总计 {0} 项，通过 {1} 项，失败 {2} 项" -f $results.Count, ($results.Count - $failed), $failed) -ForegroundColor $(if ($failed -eq 0) { 'Green' } else { 'Red' })
exit $failed
