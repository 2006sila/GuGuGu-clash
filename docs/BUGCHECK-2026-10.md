# GuGuGu-clash 代码体检 + box4/Surfing 可学清单

审计范围：`app/src/main/**`（Kotlin 61 个文件 + assets/tproxy.sh）、`scripts/recover.sh`。
未在设备上实测（adb 不通），下面每条都是静态分析结论，已标注确认程度。
现有单测：**186 个 / 22 个套件 / 0 失败**（`gradle --offline :app:testArm64DebugUnitTest --rerun`）——下面所有问题都在测试覆盖之外。

---

## 一、Bug 清单

### P1-1 内核复用判定失效：每次启动都会杀掉正在服务的内核

- **位置**：`TetherManager.kt:100-109` + `ShareService.kt:380-385`
- **成因**：`listeningPortsDetailed()` 只把 `[redirPort, tproxyPort, dnsPort]` 放进 state map，而 `portsListening()` 读的是 `rep.state[p.mixedPort]` —— **键根本不存在**，返回 null，`null == true` 为 false。
- **默认必然触发**：`Prefs.mixedPort` 默认 7890（`Prefs.kt:93`），只有把它设成 0 才走 `mixedPort <= 0` 的短路。
- **症状**：`ShareService.kt:186` 的 `if (restUp && !portsUp)` 恒真 → 发现存活内核也一律 `CoreManager.stop()` + sleep 1s + 重启内核。注释 176-183 行明确说这个行为"实测把用户电脑断过"，而它现在每次启动都会发生；191-193 行的"直接复用（不重启、不断网）"是死分支。
- **修法**：`val ports = listOf(p.redirPort, p.tproxyPort, p.dnsPort) + listOfNotNull(p.mixedPort.takeIf { it > 0 })`。

### P1-2 独立守护在 App 路径下起不来（且唤醒后会自杀）

- **位置**：`assets/tproxy.sh:85`（`setsid sh "$0" watch`）+ `RootShell.kt:19-30`
- **成因**：App 侧从不把 `tproxy.sh` 落盘，而是把脚本文本**写进 su 的 stdin**（`TetherManager.script()` → `RootShell.run()`）。所以脚本里的 `$0` 是 shell 自己的名字（sh/su），不是脚本路径 → `setsid sh sh watch` 会去打开名为 `sh` 的文件并立刻失败退出。`Emergency.kt:39` 的 `pkill -f 'tproxy.sh watch'` 同样匹配不到，侧面印证这条链路在 App 侧从未真正建立。
- **症状**：注释里承诺的"App 被 force-stop 后由独立守护摘规则"**不生效**；只剩 App 内 60 秒巡检兜底，而它的前提正是 App 还活着。
- **附带问题**：`hs_watch_run` 检测到内核消失后调 `hs_unapply` → `hs_unapply:139` 先 `hs_watch_stop` → `kill $(cat $HS_WATCH_PID)`。若 pid 文件里存的确实是守护自己的 pid（`setsid` 未 fork 时就是这样），守护会在**清理只做了一半时把自己杀掉** → 规则残留、电脑断网，正好是它要防的那件事。
- **修法**：脚本开头 `SELF=$(realpath "$0")`，App 侧改为先把脚本写到 `/data/local/tmp/guguguclash/tproxy.sh` 再执行；守护启动后自己写 `echo $$ > $HS_WATCH_PID`；清理时传参跳过 `hs_watch_stop`。

### P2-1 网络联动先落盘后校验，失败会留下"UI 与实际不一致"

- **位置**：`NetworkWatcher.kt:117-122`
- **成因**：`Prefs.save(currentProfileId = hit.profileId)` 在 `prepareConfig` **之前**执行；新配置自检失败时只 log，不回滚。
- **症状**：落盘的 currentProfileId 指向一份没通过自检的配置，而内核还在跑旧配置 —— 与 `ShareService.kt:155-164` 专门修过的那类不一致同源。
- **修法**：prepareConfig 成功后再 save；失败分支回滚 `currentProfileId`。

### P2-2 `lastApplied` 提前置位，失败后不重试

- **位置**：`NetworkWatcher.kt:109 / 115`
- **成因**：`lastApplied = id` 在真正执行前赋值，配合 109 行的 `hit.profileId == p.currentProfileId && lastApplied == id` 短路。
- **症状**：热重载/自检失败后，同一网络下再也不会重试，要等网络标识变化。
- **修法**：只在成功分支写 `lastApplied`。

### P2-3 紧急恢复的接口清单与检测前缀不同源

- **位置**：`Emergency.kt:17-20`、`scripts/recover.sh:6` vs `tproxy.sh:38` + `TetherDetector.kt:11`
- **成因**：检测接受 `ap*|wlan*|softap*|swlan*|rndis*|usb*|bt-pan*|eth*`（含 ap2、swlan1、wlan3、eth1…），而恢复脚本只枚举 12 个固定名字。
- **症状**：当检测选中的是名单外接口，紧急恢复**摘不掉 nat/mangle/FORWARD 跳转**，电脑继续断网 —— 而它正是为这种情况准备的。
- **修法**：改成 `ip -o link show` 动态枚举，或由 Kotlin 侧统一生成清单。

### P2-4 注释与常量自相矛盾（uid 2000 已不是运行身份）

- **位置**：`CoreManager.kt:32-39 / 59-64 / 151`、`CoreInstaller.kt:32 / 97-101`
- **事实**：实际调用是 `runAsUid = null`（root，`ShareService.kt:194-196`），而 `ShareService.kt:172-175` 的注释明确写着"唯一可行的是 root，uid 2000 建不了 redir/tproxy"。`CoreManager` 与 `CoreInstaller` 的注释却是相反结论，`RUN_AS_UID = 2000` 只剩 `CoreInstaller` 拿它做 chgrp 用。
- **风险**：照注释把 `runAsUid` 改成 2000 会直接打断透明代理监听。
- **修法**：把 `RUN_AS_UID` 重命名为 `FILE_GROUP_GID` 并注明"仅用于给运行目录设组权限"，同步改注释。

### P3-1 mark/table 常量四处重复且脚本内不可覆盖

- **位置**：`RuleBuilder.kt:12-14`、`tproxy.sh:18-22`、`Emergency.kt:36-37`、`recover.sh:16-17`
- **问题**：`HS_MARK=2025` / `HS_TABLE=100` 在脚本里是硬编码（不像 HS_REDIR/HS_TPROXY/HS_DNS 那样支持 env 注入），改一处漏一处就会清不干净。
- **修法**：脚本改成 `[ -z "$HS_MARK" ] && HS_MARK=2025`，由 `RuleBuilder.env` 统一注入。

### P3-2 `-w 5` 偏小

- **位置**：`tproxy.sh` 全部 24 处 `iptables -w 5`（`RuleScriptTest.kt:123-131` 还专门断言每条都必须带 `-w`）
- **对照**：box4magisk 与 Surfing v7 全篇 `-w 100`。
- **修法**：统一升到 `-w 100`，同步改断言里的 `replace("-w 5 ", "")`。

### P3-3 独立命令行用法缺少"内核活着"前置校验

- **位置**：`tproxy.sh:89-136`（`hs_apply`）
- **问题**：App 路径有 `portsListening` 把关，但 `su -c 'sh tproxy.sh on'` 这条独立用法会直接装规则；内核没起来时客户端流量被 REDIRECT 到不存在的端口 → 断网。
- **对照**：两模块都有 `probe_user_group()`（pidof + `stat -c %u/%g /proc/$pid`），拿不到就拒绝建规则。
- **修法**：apply 前加一段 pidof/端口探测，不满足就 `ERR` 退出。

### P3-4 死代码

- `CoreManager.probeEgress()`（`CoreManager.kt:162-168`）无调用者，实际用的是 `CoreApi.probeEgress()`；两个同名函数容易改错地方。
- `TetherManager.listeningPorts()` / `TetherManager.isActive()` 无调用者。

---

## 二、box4magisk / Surfing v7 可学清单（按对 GuGuGu-clash 的实际价值排序）

| # | 学什么 | 对应 GuGuGu-clash 现状 | 落地动作 |
|---|---|---|---|
| 1 | `inotifyd` 监听 `/data/misc/net` 的写事件当作网络变化信号（源码注释直言：`/proc` 不支持 inotify，轮询是坏方案） | `refreshTether()` 只在**接口名变化**时装规则（`ShareService.kt:392-405`），网段/网关变化不触发 | 用 inotify 事件补一次 `TetherManager.apply` |
| 2 | 防回环放行要"重算本机地址集合"：`ip -4 a` 逐条地址 `-d <local> -p udp ! --dport 53 ACCEPT` + `! -p udp ACCEPT` | `tproxy.sh:25` 是固定私网段清单（含 10/8、192.168/16），热点网段变化时够用但依赖清单 | 保留清单，追加本机地址动态放行 |
| 3 | 幂等：`iptables -C` 查过再 `-A`（box4 裸 `-A` 会累积重复规则，是反面教材） | `tproxy.sh` 用 `-D` 再 `-I` 已半幂等；`HS_BLOCK` 用 `-C \|\| -A` 已对齐 | 只有新增规则时保持该习惯 |
| 4 | 事件去抖：2 秒 `RULES_LOCK`、15 秒 `LINKCLEAR_LOCK`、开机 60 秒稳定期（`/proc/uptime < 360`） | 无去抖；开关抖动/网络切换可能连续 apply | 在 `TetherManager.apply` 外层加时间锁 |
| 5 | 建规则前确认核心活着：`pidof` + `stat -c %u/%g /proc/$pid`（`probe_user_group`） | App 路径有，独立路径没有（见 P3-3） | 见 P3-3 修法 |
| 6 | 内核能力用"试插"探测，不读 `/proc/config.gz` | `tproxy.sh:109` 已经是试插 TPROXY 后回退 —— **已对齐，无需改** | — |
| 7 | 切网后 `DELETE /connections` 清旧连接 | 无 | `NetworkWatcher` 切配置后加一次 |
| 8 | stop 要么记运行时配置、要么全链表清（`destroy_all_rules` 遍历 `mangle nat filter`） | `hs_unapply` 遍历当前有地址的接口来删跳转，接口消失时会漏（见 P2-3） | 改成"遍历所有接口 + 清链"，与 Emergency 合并成一份 |
| 9 | `settings put global private_dns_mode off`（并备份恢复） | 未处理；Android 强制 DoT 会绕过 DNS 劫持 | 参照 `TetherOffload.kt` 的 supported/backup 模式再做一个 |
| 10 | 热点侧按客户端 MAC / SSID 过滤（box4 `MAC_CHAIN`、Surfing v7 `ctr.inotify`） | 有 `ClientMonitor.block/unblock` 但需手动点 | 自动规则交给用户决定 |
| 11 | 模块开关零重启：监听 disable 文件存在性 | 已有开关，但改动配置后靠热重载 | — |
| 12 | 反面教材：接口名写死（box4 `HOTSPOT_INTERFACE="wlan2"`）、`"$iptables"` 整条命令塞变量、单文件 1900 行、默认开 `BLOCK_QUIC`/`PROXY_IPV6=-1` 这类全局副作用 | GuGuGu-clash 用 `ip route show table local_network` + 前缀匹配，方向更对 | — |

---

## 三、复现与验证

```powershell
# 单测（沙箱内可用：分布与依赖都在 build-env 里）
$env:GRADLE_USER_HOME='D:\Agent\workplace\Ordinary\build-env\gradle-home'
$env:ANDROID_HOME='D:\Agent\workplace\Ordinary\build-env\sdk'
& 'D:\Agent\workplace\Ordinary\build-env\gradle\gradle-8.7\bin\gradle.bat' --offline :app:testArm64DebugUnitTest --rerun
```

设备侧验证 P1-1（需 adb）：先开着共享、确认内核在跑，然后重开 App，观察日志里是否出现"发现残留内核但监听不全，先清理再重启"，以及 `ip rule` / 端口是否在重启期间抖动。

---

## 四、修复记录（本轮已改）

改动 12 个文件、+380/-150 行；验证方式：Kotlin 编译（main + 单测）、**195 个单测全绿**（较修复前 186 个新增 9 个回归用例）、`:app:assembleArm64Debug` 打包成功。

| 编号 | 文件 | 改法 |
|---|---|---|
| P1-1 | `TetherManager.kt`、`ShareService.kt` | 新增纯函数 `portsToCheck()` / `allPortsListening()`，mixed 端口并进集合，判据与集合同源 |
| P1-2 | `TetherManager.kt`、`tproxy.sh` | 脚本先落盘到 `/data/adb/guguguclash/run/tproxy.sh` 再 `sh <路径> <动作>`；新增 `HS_SELF`（仅绝对路径）；守护 `echo \$\$ > pid` 自己写；`hs_unapply keep-watch` 不再自杀；落盘失败自动退回 stdin 注入 |
| P2-1 / P2-2 | `NetworkWatcher.kt` | 先 `prepareConfig` 通过才 `Prefs.save`；`lastApplied` 只在成功分支写；加 30 秒失败重试闸 |
| P2-3 | `Emergency.kt`、`scripts/recover.sh` | 动态枚举（`ip -o link show`，剥 `@ifX`）∪ 静态候选名单；recover.sh 优先调用落盘脚本 |
| P2-4 | `CoreManager.kt`、`CoreInstaller.kt` | `RUN_AS_UID` → `FILE_GROUP_GID`，注释改为"内核以 root 运行"并写明复测结论 |
| P3-1 | `RuleBuilder.kt`、`tproxy.sh`、`Emergency.kt` | `HS_MARK`/`HS_TABLE` 由 env 注入（脚本保留默认值），Emergency 从 RuleBuilder 取常量 |
| P3-2 | `tproxy.sh`、`Emergency.kt`、`recover.sh` | 全部 `-w 5` → `-w 100`（含 ip6tables），断言同步 |
| P3-3 | `tproxy.sh` | `hs_apply` 写规则前先 `pgrep -f 'guguguclash/bin/mihomo'`，没有就拒绝安装 |
| P3-4 | `CoreManager.kt`、`TetherManager.kt` | 删除无调用者的 `CoreManager.probeEgress`、`TetherManager.listeningPorts/isActive` |

新增/加强的回归用例：
- `TetherRulesTest`：mixed 端口必须在集合里、缺失键必须判不齐、集合与解析器同源（4 个）
- `RuleScriptTest`：守护必须用脚本路径重启自己 + 自己写 pid + 不自杀、mark/表号可注入、apply 先查内核（3 个）
- `EmergencyTest`：动态枚举当前接口、mark/表号来自 RuleBuilder（2 个）

未验证项（沙箱限制，非代码问题）：
- `sh -n` 语法检查：本机没有可用的 POSIX shell（Git bash 在沙箱下报 signal pipe 失败）。建议设备上补一次：
  `adb shell su -c 'sh -n /data/adb/guguguclash/run/tproxy.sh'`
- 真机行为：P1-2 的守护是否真的能在 force-stop 后存活，需要在设备上按上面 P1-1 的步骤复验。

---

## 五、设备连上后的复检（第二轮，一加 PJZ110 / ColorOS 16 / Android 16）

设备状态：adb 已连（serial 3B1592002QZ00000），装的是 **1.0.1**（仓库 HEAD 已是 1.0.2，本轮修复都要重装才生效）；
共享正在跑（mihomo 以 root 运行、配置目录 /data/adb/guguguclash/conf，25 条连接、下行 7.3 GB），电脑的流量确实在核心里。
adb 侧只能以 shell(2000) 操作 —— auto 模式禁止提权，所以 iptables -S 与 /data/adb 都读不到，
本轮靠 logcat + 内核 REST API（adb forward 19090→9090）+ 从电脑侧做流量实验取证。

### 新发现 P0-1：热点客户端的 DNS 没被劫持，共享流量只能按 IP 分流

证据链：

1. 电脑拿到的 DNS 就是热点网关：ipconfig /all → DNS Servers . . . : 10.x.x.x；
2. tproxy.sh 的 nat 链顺序是「私网放行(RETURN) → 53 重定向」，而 10.x.x.x 落在 HS_NETS 的 10.0.0.0/8 里
   → 客户端解析请求命中 RETURN，永远走不到 53 重定向；
3. 内核日志里电脑的连接全是 IP 匹配：
   [TCP] 10.x.x.100 → 39.136.117.190:443 match IPCIDR(...) using 国内网站[DIRECT]
   [TCP] 10.x.x.100 → 150.171.28.11:443 match Match using 漏网之鱼[香港]
4. 端到端实验：电脑 Resolve-DnsName www.github.com -Server 10.x.x.x → 20.205.243.166，
   再连它的 443，内核里查到的是 rule=Match、payload 空、host 空、chains=香港>手动切换>漏网之鱼 ——
   内核从没见过这次解析，所以域名规则、广告拦截、机场自带分组对共享流量全部失效。

修法：hs_apply 里把两条 53 REDIRECT 提到链首（在 HS_NETS 与本机地址放行之前），
RuleBuilder.previewCommands 同步；两个「钉住旧顺序」的断言翻过来
（dnsHijackComesBeforeEveryBypass / localAddressesAreBypassedAfterDnsHijack）。
两个模块也是这个顺序：Surfing v7 用独立的 CLASH_DNS_EXTERNAL 链插在 BOX_EXTERNAL 之前。

### 新发现 P2-5：专用 DNS 的键名在这台机器上不存在（上一轮抄的功能是空转）

settings get global private_dns_mode → null；settings list global 里只有 **private_dns_default_mode=off**。
box4magisk / Surfing v7 与上一轮的本实现都只认 AOSP 键，在这台 ColorOS 上等于没做。
修法：PrivateDns.KEYS 依次探测 private_dns_mode → private_dns_default_mode，读写与恢复都带键名。

### 新发现 P2-6：内核 info 级日志把 logcat 冲爆，应用自己的日志一条不剩

/configs 显示 log-level=info；logcat 里 GuGuGu-clash 这个 tag 只剩 16 行、全落在同一秒，
全是 core[OUT] ... [TCP]/[UDP] ... match ... 的连接日志 —— 启动、规则安装、出口探测那些有用的行已被挤出去。
修法：CoreManager.shouldMirrorToLogcat() 不再把 info 级「每连接一行」镜像到 logcat（文件与内存环照旧全量）。

### 已核查、不是 bug

- BootReceiver 虽然 exported=true，但 onReceive 只认 BOOT_COMPLETED / LOCKED_BOOT_COMPLETED /
  MY_PACKAGE_REPLACED 三个受保护广播（第三方应用发不出来），还要求 autoStartOnBoot 且已启用，无滥用面。
- 崩溃 / ANR：logcat -b crash 与主缓冲都没有 io.guguguclash 的记录；CPU 0.0%，无忙等。
- 电池优化白名单当前没加（dumpsys deviceidle whitelist 无匹配），但 App 有完整入口
  （权限检查页「电池优化白名单」+ 一键修复），属于用户待办而非缺陷。

### 待 root 复验 / 已知能力缺口

1. ip route show table local_network 在 shell 下报 table id value is invalid，/data/misc/net/rt_tables 读不到 ——
   无法判断 root 下这张表是否存在（接口检测目前实际走「前缀 + 私网」回退，效果正常）。设备上补：
   su -c 'ip route show table local_network; cat /data/misc/net/rt_tables'
2. 内核 ipv6: true 且客户端 v6 被 ip6tables -I FORWARD -i wlan2 -j DROP 阻断：
   本应用没有 IPv6 透明代理，只能掐掉（客户端先试 v6 再回落，首包偏慢）。
   两个模块有完整的 ip6tables TPROXY 路径 —— 这是它们确实更强的一处。
3. vgate0 172.30.x.x/32（ColorOS 双通道加速）存在；客户端流量走 wlan2 被 tproxy 抓住，目前无影响。

---

## 六、第二轮实测结果（把修复装到设备上验证）

安装方式：build-env 的 Gradle 8.7 构建 arm64 debug APK（42.8 MB）→ adb install -r（保留数据）→
启动 App 后由界面开关拉起共享。设备负载偏高（load average 约 12），这点后面很关键。

### 验证通过

| 修复 | 设备上的证据 |
|---|---|
| P0-1 DNS 劫持顺序 | 电脑系统解析 www.github.com / www.baidu.com 拿到 198.18.0.27 / 198.18.0.33（fake-ip），不再是运营商真实 IP；内核连接列表出现带域名的记录：DomainSuffix/bilivideo.com、biliapi.net、hdslb.com → 命中机场自己的「B站港澳台」组。21 条连接里 5 条带 host（修前是 0） |
| P0-1 端到端 | Node 侧解析 baidu → 198.18.0.33，裸 TCP 连接成功，fetch https://www.baidu.com = 200、https://www.github.com = 200（后者经过代理节点） |
| P1-1 mixed 端口复用 | 日志出现「内核已在运行且端口齐全，直接复用（不重启、不断网）」，内核 PID 保持不变，不再被杀掉重启 |
| P1-2 独立守护 | 日志出现「守护已启动 (pid …)」，设备上第一次真的存在 tproxy.sh watch 进程（旧版完全没有） |
| P2-5 专用 DNS 键名 | 把设备值改成 opportunistic 再开共享，日志「已关闭系统专用 DNS（private_dns_default_mode=opportunistic，停止共享时恢复）」，按键名读写成功；停止时恢复 |
| P2-6 logcat 降噪 | 应用自己的日志（启动 / 规则 / 探测 / 复用）重新可见，不再被每连接日志挤掉 |

### 验证过程中暴露的新 bug（P0-2，已修）

现象：修好复用之后，服务在 RUNNING 后 3 秒内自停，日志连报三次
「检测到内核已退出，停止服务以恢复电脑上网」，随后 stopAll 跑了三遍，电脑当场回到直连。

根因：CoreManager.isRunning() 只看 proc?.isAlive，而**复用路径不会调 start()**，proc 恒为 null
→ 判活恒 false → 巡检把活着的内核当死内核。旧实现每次都杀内核重启，所以 proc 永远有值，这个洞一直没暴露。

修法：
1. 新增 adopted 标记（复用成功时置位），无句柄时改走外部信号；
2. alive() 抽成纯函数便于单测；
3. 判活用**两条独立信号取或**：su 下 pgrep -f 'guguguclash/bin/mihomo' 与 REST /version，
   只有两条都说不在才判死 —— 第一版只用 REST，设备高负载时 REST 偶发超时，实测又停了一次；
4. stopAll 加 stopQueued 闸，避免网络回调连击导致重复排队、重复清理。

验证：adb shell am force-stop io.guguguclash 后内核存活（PID 26180）→ 重启 App 显示「直接复用」→
服务进入 RUNNING 且持续 4 分钟以上不再自停，连接列表持续更新（21 条、5 条带域名）。

### 仍未解决 / 需留意

- curl.exe 与 PowerShell 的 Invoke-WebRequest 走 fake-ip 时连接超时（000），但同一时刻
  Node 的原生 TCP/fetch 与电脑上的正常应用都能经 fake-ip 出网并命中规则 —— 判定为这两个
  命令行探针自身的兼容问题，不是应用缺陷；若日后确有 Windows 应用不兼容 fake-ip，
  可在「网络 → DNS 模式」把 enhanced-mode 改成 redir-host。
- IPv6 无透明代理（只能 FORWARD DROP），模块有完整 ip6tables TPROXY 路径。
- ip route show table local_network 仍待 root 复验。

---

## 十一、真机验证（第三轮）与随之发现的「采纳模式规则失效」

### 验证通过（一加 PJZ110 / ColorOS 16，root 全程可用）

| 验证项 | 结果 |
|---|---|
| 新增 3 个开关出现在「网络 → 开关」 | ✔ 开机自启（关）/ 代理 UDP（开）/ 阻断客户端 IPv6（开），与 prefs 一致 |
| 开关真的写进规则 | ✔ udp=true → `-A PREROUTING -i wlan2 -j HS_MANGLE` + `TPROXY --on-port 7893 --tproxy-mark 0x7e9` + `ip rule fwmark 0x7e9 lookup 100`；v6=true → `ip6tables -A FORWARD -i wlan2 -j DROP` |
| 运行模式即时生效 | ✔ 切「全局」→ 立刻 /configs mode=global；切回「规则」→ 立刻 mode=rule，全程不需重启共享 |
| 全局模式的 GLOBAL 兜底 | ✔ 日志「全局模式：GLOBAL 原本不是可用节点，已切到「🇭🇰香港 直连 0.1x」（可用 40 个）」；随后电脑侧 google = 204 |
| 分流页不再有「直连域名」 | ✔ 只剩 规则 / 节点 / 应用 / 查证 四段 |
| 分类追加域名编辑器（新功能） | ✔ 长按「哔哩哔哩」→ 弹框 → 输入后保存 → 行副标题变「另加 1 个域名」、prefs 写入；重启后 config.yaml 的 rules 块**最前面**出现注入行；清空保存后同步消失 |
| 独立守护真正存在 | ✔ 设备上能看到 `sh /data/adb/guguguclash/run/tproxy.sh watch` 进程 |
| 内核复用 | ✔ 「内核已在运行且端口齐全，直接复用（不重启、不断网）」，之后服务保持 RUNNING 不自停 |

### 新发现并修复：采纳模式下，规则页的设置全部无效

**现象**：在采纳模式（profileMode=adopt）下用新编辑器加了域名，重启后生成的 config.yaml 里**一条都没有**。

**根因**：`ConfigBuilder.buildAdopted` 只处理受控段（dns/tun/端口/嗅探…），**从不注入用户规则**；
只有重建模式走 `ruleLines`。也就是说采纳模式下「内置分类走法 / 自定义规则 / 追加域名」全是摆设 ——
而采纳模式恰恰是本项目的主推模式，所以这是「界面能用、实际无效」的典型。

**修法**：新增 `injectUserRulesInPlace`，把用户规则插到订阅 rules 块的最前面（用户意图优先，与重建模式一致）；
缩进沿用订阅自己的写法（机场多用 4 空格，写错缩进会让整份 YAML 解析失败）；
「走代理」自动改写成**订阅里真实存在的组名**（取它自己 rules 里 MATCH 后面的那个组），
避免内核因「组不存在」拒绝加载整份配置；实在找不到组名时，宁可丢弃 PROXY 规则也不让配置起不来。

**验证**：新增 2 个单测（217/217 全过、0 警告），真机确认注入行出现在 `rules:` 之后、
内核自检通过、服务进入 RUNNING、电脑侧 google=204。


---

## 十、死代码与残留清理（第三轮）

第二轮扫出来的「函数级 / 资源级 / 清单级」问题，本轮全部处理完：

**删除（14 处无调用函数，逐项确认过「除声明处外零引用」）**
- 日志/状态：ShareState.listeners 订阅设施（含 log() 里的通知循环）、addListener、removeListener、clearLog、clearHistory
- 内核管理：CoreManager.recentLog（连带只写不读的 ring 字段与写入点）、CoreApi.selectedNow、CoreApi.testGroupDelay
- 未接线 API：CoreDownloader.importRulesetDir
- 工具：RootShell.runScript、RootShell.suVendor、TetherOffload.isDisabled、RuleCatalog.byKey、RuleBuilder.previewCommands
- 以及只服务于上述函数的测试（previewListsOrderedCommands；RuleCatalogTest 改用 ALL.first{...} 不依赖 byKey）

**补上/接线（2 项，「有数据通路没入口」的半截功能）**
- extraDomains（给内置分类追加域名）：规则页「长按分类」打开编辑器，按分类单独编辑、保存时用
  CustomRule.extraDomainsText 合并回整份文本（parseExtraDomains/extraDomainsText 往返，新增单测钉住）；
  分类行副标题会显示「另加 N 个域名」；帮助页补了一句说明。
- CoreApi.patchMode（运行模式即时生效）：网络页切模式后 PATCH /configs 立刻生效，不必重启共享；
  切到全局时顺手 ensureAliveNode("GLOBAL") 兜底一次。

**清单/资源残留**
- 删掉清单里的幽灵项 .ui.TrafficActivity（声明了但类文件根本不存在）
- 删掉 7 个未使用字符串（value_off / entry_bootstrap / dlg_sub_title / dlg_sub_message / dlg_sub_hint / action_import / action_update_current）
- 删掉未使用的布局 id cardTraffic（include 里的控件本身都在用）

**过程中踩的坑（记录一下）**：批量删函数时，我对「单表达式函数」（`fun f() = ...`，没有花括号）也套了
花括号配对，结果把后面的函数一起吃掉了（RootShell.isRooted、TetherOffload 的 object 收尾、CoreApi 半截残留）。
编译立刻报错，已全部修复并复验 —— 结论：删代码的脚本必须以「编译通过」为收口，不能只看删了多少字符。

**验证**：25 suites / 215 tests / 0 failures / 0 warnings；debug 与 release 变体均可打包。


---

## 八、功能体检：重复 / 失效项清理（本轮一并做掉）

方法：把 Prefs 的 80 个字段逐个在全仓搜引用（区分「界面能改吗」「代码消费吗」），
再把 19 个 Activity 的引用关系与所有 addEntry 入口拉出来横向比。

**判定为失效的**
1. `dnsForceMapping`：声明 + load + save 齐全，但**全仓零消费**，ConfigBuilder 也从不输出 force-dns-mapping
   —— 设置存在却完全不起作用。→ 已删除（字段、读取、写入三处）。
2. `autoStartOnBoot`：默认 false，唯一消费点是 BootReceiver，而 19 个页面里没有任何入口
   —— 「开机自启共享」实际不可达。→ 已在「网络 → 开关」补上入口。

**判定为重复的**
3. 「直连域名」(`customDirectDomains`) 与「自定义规则→直连」：两者最终都生成
   `DOMAIN-SUFFIX,<域名>,DIRECT`（ConfigBuilder.ruleLines 里两处一模一样）。
   → 已合并：新增 CustomRule.mergeDirectDomains(纯函数、幂等、大小写无关去重)，
   ProfileRunner 在生成配置时做一次性迁移（并入后清空旧字段并在日志里说明），
   分流页的「直连域名」入口与对话框已移除；ConfigBuilder 保留旧字段的兼容分支以防迁移未跑。
4. 「权限检查」在诊断页与属性页各有一个入口 → 已移除属性页那个（该页仍保留权限就绪度汇总）。

**会造成意外行为的**
5. 采纳模式 + 流量嗅探默认关 = 把订阅自带的 sniffer 段整段删掉
   （buildAdopted 的 else 分支 removeTopBlockInPlace）。→ 已改为「关着就不动订阅的」，
   「流量嗅探」的副标题也改成「用我们的配置覆盖订阅自带的 / 保留订阅自带的」。
   回归用例：adoptedConfigKeepsAirportSnifferWhenOursIsOff / adoptedConfigReplacesSnifferWhenOursIsOn。
6. 「运行模式」是内核级开关，同时作用于热点客户端，界面却没说 → 标签与副标题已写明
   （「全局（全部走代理，含电脑）」），并补上 GLOBAL 组兜底（见第九节）。

**同时补齐的隐藏开关**（有代码消费、界面无入口）：`proxyUdp`、`blockIpv6`、`autoStartOnBoot`
三项加进「网络 → 开关」。其余（bindAddress / dnsHijackAny / historyMinutes / extraDomains /
dnsDomainFallback / dnsPreferH3 / dnsAppendSystemDns / extAllowOrigins / extAllowPrivateNetwork /
adoptSubscriptionGroups / adoptSubscriptionRules）属高级项，保持隐藏。

**核查过没问题的**：不存在「界面能改但代码不读」的设置（UI-only = 0）；19 个页面全部有引用，无死页面；
`dnsHijackAny`(`tun.dns-hijack`)、`extraDomains`、`bindAddress` 都有真实消费。

---

## 九、全局模式：GLOBAL 组兜底

全局模式下 rules 全被忽略，一切交给内核自带的 GLOBAL 选择器；而它把 DIRECT/REJECT 也算作候选，
默认常常停在「直连」—— 于是「全局」变成「全部直连」，手机与电脑的谷歌都打不开（实测踩到）。

修法：startAll 的自动优选里补一段 —— 当 `mode == "global"` 时对 GLOBAL 也调一次
`ensureAliveNode("GLOBAL")`（只在当前不是可用真节点时才切，不覆盖用户手选），并把结果写进日志。


---

## 七、收尾：另一会话复核提出的两个遗留问题（已修）

另一会话（工作区同名项目）对本轮修复做了独立复核，结论是「文档里列的问题全部属实、修法都对」，
并指出两个遗留问题。我逐条核对后确认属实，已按下面方式落地。

### ① 专用 DNS 的原值只存在内存 → 进程被杀 / 手机重启后永久丢失

原实现把原值放在 ShareService 的内存字段里，只有 stopAll 会恢复。共享期间进程被强杀、或手机重启后
开机自启重新 startAll 时读到的已经是 off，于是永远不会恢复 —— 用户原本的私人 DNS 设置被静默改掉。

修法（原值落盘）：
- Prefs 新增 privateDnsSavedKey / privateDnsSavedValue（键名 + 值一起存）；
- startAll 三种情况：盘上有记录就沿用它（停止时还给用户）；用户已关掉本功能但盘上还有记录 → 立即还回去再撒手；
  「本来就是 off」→ 不写任何东西并把记录清掉；
- stopAll 按同一键恢复，成功后清空记录；
- 两处落盘都改成携带更新后的 Prefs 快照（否则 startAll 末尾那次 save 会把刚写的记录覆盖掉）。

### ② 共享接口永久消失时规则残留

refreshTether 原本对「接口消失」只记日志、不动规则（避免热点一闪就拆了再装）。
但独立守护只在**内核没了**时才摘规则，而关掉热点时内核还活着 → 规则会一直留在内核里到下一次 stopAll
（表现是界面显示共享中、实际没有共享）。

修法：连续两次巡检（约 2 分钟）判空才主动 cleanup 一次；第一次仍只记日志（保住「热点一闪不折腾」的性质），
摘除后安静等待，接口回来由 tetherKey 变化自动补装。

### ③ 补齐 scripts/recover.sh 的交叉校验（另一会话列为待办但未落地）

新增 RecoverScriptTest：校验 fwmark/路由表号与 RuleBuilder 常量一致、候选接口名单覆盖
RuleBuilder.CANDIDATE_IFACES、优先调用设备上落盘的 tproxy.sh、会改规则的 iptables 调用都带 -w 100。

### 验证

:app:testArm64DebugUnitTest :app:assembleArm64Debug --rerun-tasks
→ 25 suites / **210 tests / 0 failures / 0 errors / 0 warnings**（较上一轮 205 增加 5 个 recover.sh 用例）。
APK 已重新构建，**尚未安装到设备**（装机会让电脑断网几秒，等确认后再做）。



