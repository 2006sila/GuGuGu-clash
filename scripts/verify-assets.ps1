# 校验内置资产是否与 scripts/asset-checksums.txt 一致
# 用法: powershell -File scripts/verify-assets.ps1 [-Update]
param([switch]$Update)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$manifest = Join-Path $PSScriptRoot "asset-checksums.txt"

$entries = @()
foreach ($line in Get-Content $manifest) {
  $t = $line.Trim()
  if ($t -eq "" -or $t.StartsWith("#")) { continue }
  $parts = $t -split "\s+"
  if ($parts.Count -lt 3 -or $parts[0] -ne "sha256") { continue }
  $entries += [pscustomobject]@{ hash = $parts[1].ToLower(); file = $parts[2] }
}

if ($entries.Count -eq 0) { throw "清单里没有可校验条目" }
if ($Update) { throw "-Update 尚未实现：请手工把下面输出拷回 asset-checksums.txt" }

$bad = 0; $missing = 0
foreach ($e in $entries) {
  $p = Join-Path $root $e.file
  if (-not (Test-Path $p)) { Write-Host ("  ⚠ 缺失   " + $e.file); $missing++; continue }
  $actual = (Get-FileHash -Path $p -Algorithm SHA256).Hash.ToLower()
  if ($actual -eq $e.hash) {
    Write-Host ("  ✅ " + $e.file + "   " + [math]::Round((Get-Item $p).Length/1MB,2) + " MB")
  } else {
    Write-Host ("  ❌ " + $e.file)
    Write-Host ("       期望 " + $e.hash)
    Write-Host ("       实际 " + $actual)
    $bad++
  }
}

Write-Host ""
if ($bad -eq 0 -and $missing -eq 0) {
  Write-Host "全部资产校验通过（$($entries.Count) 项）"
} else {
  Write-Host "未通过: 不一致 $bad 项, 缺失 $missing 项"
  exit 1
}
