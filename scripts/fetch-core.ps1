# 下载 mihomo Android 二进制 -> app/src/<flavor>/assets/mihomo/mihomo.gz
# 用法: powershell -File scripts/fetch-core.ps1 [-Version v1.19.32]
#
# 注意：mihomo 官方 release 【不提供 .sha256sum】（v1.19.32 的 60 个资产里 0 个校验文件），
#       因此这里不用上游哈希，改为下载后做真实完整性校验：
#         gzip 完整解压的总长 == gzip 尾部 ISIZE，且解压结果是以正确 e_machine 的 ELF。
param([string]$Version = "v1.19.32")

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$repo = "MetaCubeX/mihomo"
$root = Split-Path -Parent $PSScriptRoot

# ABI -> flavor 资产目录。内核按 flavor 分开存放，避免每个 APK 都塞进两份。
$map = @{
  "mihomo-android-arm64-v8-$Version.gz" = @{ flavor = "arm64"; machine = 0xB7 }
  "mihomo-android-armv7-$Version.gz"    = @{ flavor = "armv7"; machine = 0x28 }
}

function Test-CoreArchive {
  param([string]$Path, [int]$ExpectMachine)

  $fs = [IO.File]::OpenRead($Path)
  try {
    $tail = New-Object byte[] 4
    [void]$fs.Seek(-4, [IO.SeekOrigin]::End)
    if ($fs.Read($tail, 0, 4) -ne 4) { return @{ ok = $false; why = "读不到 gzip 尾部" } }
    $isize = [BitConverter]::ToUInt32($tail, 0)
    [void]$fs.Seek(0, [IO.SeekOrigin]::Begin)

    $gz = New-Object IO.Compression.GZipStream($fs, [IO.Compression.CompressionMode]::Decompress)
    $buf = New-Object byte[] 1048576
    $head = New-Object byte[] 20
    $total = 0L
    $gotHead = 0
    try {
      while ($true) {
        $n = $gz.Read($buf, 0, $buf.Length)
        if ($n -le 0) { break }
        if ($gotHead -lt 20) {
          $c = [Math]::Min(20 - $gotHead, $n)
          [Array]::Copy($buf, 0, $head, $gotHead, $c)
          $gotHead += $c
        }
        $total += $n
      }
    } finally { $gz.Dispose() }
  } finally { $fs.Dispose() }

  if ($total -ne $isize) { return @{ ok = $false; why = "解压 $total B != ISIZE $isize B（可能被截断）" } }
  if ($head[0] -ne 0x7F -or $head[1] -ne 0x45 -or $head[2] -ne 0x4C -or $head[3] -ne 0x46) {
    return @{ ok = $false; why = "解压结果不是 ELF" }
  }
  $machine = $head[18] -bor ($head[19] -shl 8)
  if ($machine -ne $ExpectMachine) {
    return @{ ok = $false; why = ("e_machine=0x{0:X} 期望 0x{1:X}" -f $machine, $ExpectMachine) }
  }
  return @{ ok = $true; size = $total; machine = $machine }
}

Write-Host "拉取 release: $Version"
$rel = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/tags/$Version" -Headers @{ "User-Agent" = "guguguclash-build" }

$failed = 0
foreach ($a in $rel.assets) {
  if (-not $map.ContainsKey($a.name)) { continue }
  $info = $map[$a.name]
  $outDir = Join-Path $root "app/src/$($info.flavor)/assets/mihomo"
  New-Item -ItemType Directory -Force -Path $outDir | Out-Null
  $gz = Join-Path $outDir "mihomo.gz"

  Write-Host "下载 $($a.name) ($([math]::Round($a.size/1MB,1)) MB) -> src/$($info.flavor)/assets/mihomo/mihomo.gz"
  Invoke-WebRequest -Uri $a.browser_download_url -OutFile $gz -Headers @{ "User-Agent" = "guguguclash-build" }

  $r = Test-CoreArchive -Path $gz -ExpectMachine $info.machine
  if ($r.ok) {
    Write-Host ("  ✅ 校验通过  解压 " + [math]::Round($r.size/1MB,2) + " MB  e_machine=0x" + ("{0:X}" -f $r.machine))
    Write-Host ("     SHA256 " + (Get-FileHash -Path $gz -Algorithm SHA256).Hash.ToLower())
  } else {
    Write-Host ("  ❌ 校验失败: " + $r.why)
    $failed++
  }
}
if ($failed -gt 0) { throw "$failed 个内核未通过完整性校验" }
Write-Host "完成。若版本号变化，请同步更新 NOTICE 与 assets/mihomo/LICENSE.mihomo。"
