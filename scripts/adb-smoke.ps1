# 真机快速验证：用 debug 广播改规则，用 monkey 拉起目标应用，观察日志
# 用法：
#   pwsh -File scripts/adb-smoke.ps1 -Mode block     # 时段外(3:00-4:00)拉王者，应在 2 秒内被弹回
#   pwsh -File scripts/adb-smoke.ps1 -Mode warn      # 计时 2 分钟+宽限 1 分钟，跑完提醒->强制退出（约 3.5 分钟）
#   pwsh -File scripts/adb-smoke.ps1 -Mode count     # 连续打开 3 次验证次数限制
param(
    [ValidateSet('block', 'warn', 'count', 'status', 'reset')]
    [string]$Mode = 'block',
    [string]$Target = 'com.tencent.tmgp.sgame'
)

$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.toolchain\android-sdk\platform-tools\adb.exe'
$env:TMP = Join-Path $root '.toolchain\tmp'
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
$PKG = 'com.discipline.control'

$devices = (& $adb devices) | Where-Object { $_ -match "`tdevice$" }
if (-not $devices) { Write-Host '没有已授权设备' -ForegroundColor Red; exit 1 }

function Dbg([string[]]$extra) {
    & $adb shell am broadcast -a "$PKG.DEBUG" -p $PKG @extra 2>&1 | Out-Null
}
function DbgRule($startHour, $endHour, $opens, $sessionMin, $graceMin, $gapMin) {
    Dbg @('--es', 'cmd', 'rule', '--es', 'pkg', $Target,
        '--ei', 'startHour', "$startHour", '--ei', 'endHour', "$endHour",
        '--ei', 'opens', "$opens", '--ei', 'sessionMin', "$sessionMin",
        '--ei', 'graceMin', "$graceMin", '--ei', 'gapMin', "$gapMin")
}
function Launch {
    & $adb shell monkey -p $Target -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
}

switch ($Mode) {
    'reset' {
        Dbg @('--es', 'cmd', 'reset-day')
        Write-Host '今日用量已清零'
        Dbg @('--es', 'cmd', 'status')
        Start-Sleep -Seconds 1
        & $adb logcat -d -v time -s 'DC/Debug:V' '*:S' | Select-Object -Last 8
    }
    'status' {
        & $adb logcat -c
        Dbg @('--es', 'cmd', 'status')
        Start-Sleep -Seconds 1
        & $adb logcat -d -v time -s 'DC/Debug:V' '*:S' | Select-Object -Last 10
    }
    'block' {
        Dbg @('--es', 'cmd', 'reset-day')
        DbgRule 3 4 3 60 20 5   # 时段故意设在凌晨 3-4 点 → 当前时间一定在时段外
        $fg = (& $adb shell dumpsys activity activities | Select-String 'topResumedActivity|mResumedActivity' | Select-Object -First 1) -join ' '
        Write-Host "改规则前前台: $fg"
        & $adb logcat -c
        Launch
        Start-Sleep -Seconds 4
        $fg = (& $adb shell dumpsys activity activities | Select-String 'topResumedActivity|mResumedActivity' | Select-Object -First 1) -join ' '
        Write-Host "拉起王者后前台: $fg"
        Write-Host '--- 日志（应看到 deny=NOT_IN_WINDOW / block ...）---' -ForegroundColor Cyan
        & $adb logcat -d -v time -s 'DC/Guard:V' 'DC/A11y:V' '*:S' | Select-Object -Last 30
        Write-Host ''
        Write-Host '判定标准：4 秒后前台不是 com.tencent.tmgp.sgame，且日志里有 "block pkg=... reason=NOT_IN_WINDOW"'
    }
    'warn' {
        Dbg @('--es', 'cmd', 'reset-day')
        DbgRule 0 24 3 2 1 0    # 全天开放 / 3 次 / 2 分钟 / 宽限 1 分钟
        & $adb logcat -c
        Launch
        Write-Host '已拉起王者，等 200 秒观察 提醒(2min) -> 强制退出(3min) ...' -ForegroundColor Yellow
        Start-Sleep -Seconds 200
        Write-Host '--- 日志 ---' -ForegroundColor Cyan
        & $adb logcat -d -v time -s 'DC/Guard:V' 'DC/A11y:V' '*:S' | Select-Object -Last 40
        Write-Host ''
        Write-Host '判定标准：先出现 "warn pkg=... elapsedMin=2"，随后出现 "finish pkg=... reason=DAY_FINISHED" 与 "block ... forced=true"'
    }
    'count' {
        Dbg @('--es', 'cmd', 'reset-day')
        DbgRule 0 24 3 60 20 1
        & $adb logcat -c
        for ($i = 1; $i -le 4; $i++) {
            Write-Host "第 $i 次打开 ..."
            Launch
            Start-Sleep -Seconds 12
            & $adb shell input keyevent KEYCODE_HOME
            Start-Sleep -Seconds 2
        }
        Write-Host '--- 日志（前 3 次 session start，第 4 次应 block NO_OPENS_LEFT）---' -ForegroundColor Cyan
        & $adb logcat -d -v time -s 'DC/Guard:V' '*:S' | Select-Object -Last 40
        Dbg @('--es', 'cmd', 'status')
        Start-Sleep -Seconds 1
        & $adb logcat -d -v time -s 'DC/Debug:V' '*:S' | Select-Object -Last 4
    }
}
