# 取回与所打包二进制对应的 mihomo 源码（GPL-3.0 分发义务 b 项）
# 用法: powershell -File scripts/fetch-core-source.ps1 [-Version v1.19.32]
param([string]$Version = "v1.19.32")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$out = Join-Path $root "dist/third-party-source"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$zip = Join-Path $out "mihomo-$Version-source.zip"
Write-Host "下载 $Version 源码归档"
Invoke-WebRequest -Uri "https://github.com/MetaCubeX/mihomo/archive/refs/tags/$Version.zip" -OutFile $zip -Headers @{ "User-Agent" = "guguguclash-build" }

$sum = (Get-FileHash -Path $zip -Algorithm SHA256).Hash.ToLower()
Write-Host ("  源码归档 " + [math]::Round((Get-Item $zip).Length/1MB,2) + " MB  SHA256=" + $sum)

$note = Join-Path $out "SOURCE-OFFER.txt"
@"
本发行包内含 MetaCubeX/mihomo $Version 的未修改二进制（GPL-3.0）。
对应源代码可从以下任一处获取：
  1. https://github.com/MetaCubeX/mihomo  tag $Version
  2. 本目录 mihomo-$Version-source.zip  SHA256=$sum
"@ | Set-Content -Path $note -Encoding UTF8
Write-Host "  已写 $note"
