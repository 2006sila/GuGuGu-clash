# 抽取常用 geosite 分类为本地规则集 -> app/src/main/assets/ruleset/
# 用途：便于用户在规则页单独调整某一类出口，并在 geo 数据损坏时兜底。
# 用法: powershell -File scripts/fetch-rulesets.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app/src/main/assets/ruleset"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$base = "https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/meta/geo/geosite"
$names = @("bilibili", "category-ads-all", "private")

foreach ($n in $names) {
  $out = Join-Path $dest "$n.yaml"
  Write-Host "下载 geosite/$n.yaml"
  $u1 = "$base/$n.yaml"
  $u2 = "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@meta/geo/geosite/$n.yaml"
  try { Invoke-WebRequest -Uri $u1 -OutFile $out -Headers @{ "User-Agent" = "guguguclash-build" } }
  catch { Write-Host "  raw 失败，改用 jsdelivr 镜像"; Invoke-WebRequest -Uri $u2 -OutFile $out -Headers @{ "User-Agent" = "guguguclash-build" } }
  $cnt = (Select-String -Path $out -Pattern '^\s*-' ).Count
  Write-Host "  -> $out  条目数=$cnt"
}
Write-Host "完成。bilibili 期望 53 条，category-ads-all 期望 910 条，private 期望 130 条。"
