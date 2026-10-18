# 截屏到 screenshots/ ，方便远程看桌面/弹窗状态
# 用法：pwsh -File scripts/adb-shot.ps1 [-Name home]
param([string]$Name = '')

$root = Split-Path -Parent $PSScriptRoot
$adb = Join-Path $root '.toolchain\android-sdk\platform-tools\adb.exe'
$env:TMP = Join-Path $root '.toolchain\tmp'
$env:TEMP = $env:TMP
New-Item -ItemType Directory -Force -Path $env:TMP | Out-Null
$dir = Join-Path $root 'screenshots'
New-Item -ItemType Directory -Force -Path $dir | Out-Null

if ([string]::IsNullOrWhiteSpace($Name)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Name = "shot-$stamp"
}
$out = Join-Path $dir "$Name.png"

# 用 Start-Process 重定向，避免 PowerShell 文本重定向把 PNG 写坏
Start-Process -FilePath $adb -ArgumentList 'exec-out', 'screencap', '-p' -RedirectStandardOutput $out -NoNewWindow -Wait

if (Test-Path $out) {
    $len = (Get-Item $out).Length
    Write-Host "已保存 $out ($([math]::Round($len/1KB)) KB)"
} else {
    Write-Host '截图失败，检查设备是否连接/授权' -ForegroundColor Red
}
