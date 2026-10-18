# 实时看 App 的判定日志（只显示自己打的 tag + 崩溃）
$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.toolchain\android-sdk\platform-tools\adb.exe'
$env:TMP = Join-Path $root '.toolchain\tmp'
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null

Write-Host '按 Ctrl+C 结束。日志 tag：DC/Guard 判定、DC/A11y 前台应用、DC/Home 桌面刷新、DC/App 进程、AndroidRuntime 崩溃' -ForegroundColor Cyan
& $adb logcat -v time -s 'DC/Guard:V' 'DC/A11y:V' 'DC/Home:V' 'DC/App:V' 'AndroidRuntime:E' '*:S'
