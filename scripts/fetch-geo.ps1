# 下载内置 geo 规则数据 + SHA256 校验 -> app/src/main/assets/geo/
# 用法: powershell -File scripts/fetch-geo.ps1 [-Lite]
param([switch]$Lite)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root "app/src/main/assets/geo"
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$base = "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest"
$files = if ($Lite) {
  @{ "geosite-lite.dat" = "geosite.dat"; "geoip-lite.metadb" = "geoip.metadb" }
} else {
  @{ "geosite.dat" = "geosite.dat"; "geoip.metadb" = "geoip.metadb" }
}

foreach ($src in $files.Keys) {
  $out = $files[$src]
  $dst = Join-Path $dest $out
  Write-Host "下载 $src -> $out"
  Invoke-WebRequest -Uri "$base/$src" -OutFile $dst -Headers @{ "User-Agent" = "vpnshare-build" }
  $expect = $null
  try {
    $sum = (Invoke-WebRequest -Uri "$base/$src.sha256sum" -Headers @{ "User-Agent" = "vpnshare-build" }).Content
    $expect = ($sum -split "\s+")[0].Trim().ToLower()
  } catch { Write-Host "  WARN 未能下载 sha256sum" }
  if ($expect) {
    $actual = (Get-FileHash -Path $dst -Algorithm SHA256).Hash.ToLower()
    if ($expect -ne $actual) { throw "SHA256 校验失败: $out 期望=$expect 实际=$actual" }
    Write-Host "  SHA256 OK  $([math]::Round((Get-Item $dst).Length/1MB,2)) MB"
  }
}
Write-Host "完成。完整版约 12.1 MB，Lite 版约 0.5 MB。"
