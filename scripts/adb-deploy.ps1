# adb 一键部署：装包 + 授予权限 + 设默认桌面 + 开无障碍 + 忽略电池优化
# 用法：pwsh -File scripts/adb-deploy.ps1            （装最新 dist/*.apk）
#       pwsh -File scripts/adb-deploy.ps1 -SkipInstall （只重授权，不重装）
param([switch]$SkipInstall)

$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.toolchain\android-sdk\platform-tools\adb.exe'
$pkg = 'com.discipline.control'
$a11y = "$pkg/$pkg.service.GuardAccessibilityService"
$homeComp = "$pkg/.ui.HomeActivity"

if (-not (Test-Path $adb)) { Write-Host "找不到 adb：$adb" -ForegroundColor Red; exit 1 }

# adb 需要可写的临时目录（否则 daemon 起不来）
$env:TMP = Join-Path $root '.toolchain\tmp'
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null

& $adb start-server | Out-Null
$devices = (& $adb devices) | Where-Object { $_ -match "`tdevice$" }
if (-not $devices) {
    Write-Host '没有检测到已授权设备。检查：① 平板已开「USB 调试」 ② 数据线不是仅充电 ③ 平板上的「允许USB调试」弹窗点了允许' -ForegroundColor Red
    & $adb devices -l
    exit 1
}
Write-Host "设备已连接：$($devices -join ', ')" -ForegroundColor Green

if (-not $SkipInstall) {
    $apk = Get-ChildItem (Join-Path $root 'dist\*.apk') | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { Write-Host 'dist/ 里没有 apk' -ForegroundColor Red; exit 1 }
    Write-Host "安装 $($apk.Name) ..."
    & $adb install -r -g $apk.FullName
}

Write-Host '--- 授权 ---'
& $adb shell pm grant $pkg android.permission.POST_NOTIFICATIONS 2>&1 | Out-Null
& $adb shell appops set $pkg SYSTEM_ALERT_WINDOW allow 2>&1 | Out-Null
& $adb shell dumpsys deviceidle whitelist "+$pkg" 2>&1 | Out-Null
# MIUI 自启动（不是所有版本都支持这个 op，失败不影响）
& $adb shell appops set $pkg AUTO_START allow 2>&1 | Out-Null

Write-Host '--- 设默认桌面 ---'
$out = & $adb shell cmd package set-home-activity $homeComp 2>&1
if ($LASTEXITCODE -ne 0 -or "$out" -match 'Error|Exception|Unknown') {
    Write-Host "自动设默认桌面失败，需要手动：设置→应用→默认应用→主屏幕应用→自律控制" -ForegroundColor Yellow
} else {
    Write-Host '默认桌面已设为 自律控制'
}

Write-Host '--- 开无障碍服务 ---'
$cur = (& $adb shell settings get secure enabled_accessibility_services | Out-String).Trim()
if ($cur -eq 'null') { $cur = '' }
if ($cur -notmatch [regex]::Escape($pkg)) {
    $new = if ([string]::IsNullOrWhiteSpace($cur)) { $a11y } else { "$cur`:$a11y" }
    & $adb shell settings put secure enabled_accessibility_services $new
} 
& $adb shell settings put secure accessibility_enabled 1

Write-Host '--- 验证 ---'
$ver = (& $adb shell dumpsys package $pkg | Select-String 'versionName=') -join ' '
Write-Host "版本      : $ver"
Write-Host "无障碍    : $((& $adb shell settings get secure enabled_accessibility_services | Out-String).Trim())"
Write-Host "默认桌面  : $((& $adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | Out-String).Trim())"
Write-Host "电池优化  : $((& $adb shell dumpsys deviceidle whitelist | Select-String $pkg | Out-String).Trim())"

Write-Host ''
Write-Host '下一步：' -ForegroundColor Cyan
Write-Host '  pwsh -File scripts/adb-logs.ps1     看实时判定日志'
Write-Host '  pwsh -File scripts/adb-shot.ps1     截屏到 screenshots/'
Write-Host '  adb shell am start -n com.tencent.tmgp.sgame/com.tencent.tmgp.sgame.SGameActivity   直接拉起王者验证拦截'
