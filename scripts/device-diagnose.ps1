# 手机端诊断脚本：把内核报错相关的全部信息收集成一个报告
# 用法：双击 scripts\device-diagnose.bat，或在 PowerShell 里执行
#        powershell -ExecutionPolicy Bypass -File scripts\device-diagnose.ps1
$ErrorActionPreference = "Continue"
$be   = "D:\Agent\workplace\Ordinary\build-env"
$adb  = "$be\sdk\platform-tools\adb.exe"
$out  = Split-Path -Parent $PSScriptRoot
$report = Join-Path $out "device-report.txt"

if (-not (Test-Path $adb)) { $adb = "adb" }

$lines = New-Object System.Collections.Generic.List[string]
function W($t) { $lines.Add($t) }
function SH($label, $cmd) {
  W ""
  W "===== $label ====="
  $r = & $adb shell $cmd 2>&1
  foreach ($l in $r) { W ("  " + $l) }
}
function SU($label, $cmd) {
  W ""
  W "===== $label ====="
  $r = & $adb shell ('su -c "' + $cmd + '"') 2>&1
  foreach ($l in $r) { W ("  " + $l) }
}

W "GuGuGu-clash 设备诊断报告"
W ("时间: " + (Get-Date).ToString("yyyy-MM-dd HH:mm:ss"))
W ""
W "===== adb devices ====="
(& $adb devices -l 2>&1) | ForEach-Object { W ("  " + $_) }
(& $adb get-state 2>&1) | ForEach-Object { W ("  state: " + $_) }

if (((& $adb devices 2>&1 | Out-String) -split "\r?\n" | Where-Object { $_ -match "\tdevice\b" }).Count -eq 0) {
  W ""
  W "!!! 设备未授权。请解锁手机并在弹窗中点「允许 USB 调试」。"
  W "!!! 若没有弹窗：开发者选项 -> 撤销 USB 调试授权 -> 拔插数据线。"
  $lines | Set-Content -Path $report -Encoding UTF8
  Write-Host "设备未授权，报告已写入 $report"
  exit 1
}

W "===== 机型 ====="
foreach ($p in @("ro.product.model","ro.product.cpu.abi","ro.build.version.release","ro.build.version.sdk")) {
  W ("  " + $p + " = " + (& $adb shell getprop $p 2>&1))
}

SU "root" "id"
SU "内核目录" "ls -l /data/adb/guguguclash"
SU "bin" "ls -l /data/adb/guguguclash/bin"
SU "conf" "ls -l /data/adb/guguguclash/conf"
SU "conf/providers" "ls -l /data/adb/guguguclash/conf/providers"
SU "conf/ruleset" "ls -l /data/adb/guguguclash/conf/ruleset"
SU "内核版本" "/data/adb/guguguclash/bin/mihomo -v"
SU "配置自检" "/data/adb/guguguclash/bin/mihomo -t -d /data/adb/guguguclash/conf -f /data/adb/guguguclash/conf/config.yaml"
SU "config.yaml 前 80 行" "head -n 80 /data/adb/guguguclash/conf/config.yaml"
SU "应用私有目录" "ls -lR /data/data/io.guguguclash/files"
SU "应用配置" "cat /data/data/io.guguguclash/shared_prefs/guguguclash.xml"
SU "mihomo 进程" "ps -A | grep -i mihomo"
SU "iptables nat 链" "iptables -t nat -S HS_NAT"

W ""
W "===== logcat（应用相关）====="
(& $adb logcat -d -t 1500 2>&1) | Select-String -Pattern "io.guguguclash|AndroidRuntime|FATAL|guguguclash" | Select-Object -Last 80 | ForEach-Object { W ("  " + $_) }

$lines | Set-Content -Path $report -Encoding UTF8
Write-Host "报告已写入: $report"
