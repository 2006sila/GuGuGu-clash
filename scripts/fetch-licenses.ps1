# 拉取第三方许可证全文 -> app/src/main/assets/
# 用法: powershell -File scripts/fetch-licenses.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

$apacheOut = Join-Path $root "LICENSE"
$mihomoOut = Join-Path $root "app/src/main/assets/mihomo/LICENSE.mihomo"
New-Item -ItemType Directory -Force -Path (Split-Path $mihomoOut) | Out-Null

Write-Host "下载 Apache-2.0 全文"
Invoke-WebRequest -Uri "https://www.apache.org/licenses/LICENSE-2.0.txt" -OutFile $apacheOut -Headers @{ "User-Agent" = "guguguclash-build" }

Write-Host "下载 mihomo LICENSE (GPL-3.0)"
Invoke-WebRequest -Uri "https://raw.githubusercontent.com/MetaCubeX/mihomo/Meta/LICENSE" -OutFile $mihomoOut -Headers @{ "User-Agent" = "guguguclash-build" }

Write-Host "完成。校验:"
Write-Host ("  LICENSE            " + (Get-Item $apacheOut).Length + " bytes")
Write-Host ("  LICENSE.mihomo     " + (Get-Item $mihomoOut).Length + " bytes")
