# 从 box4magisk / Surfing v7 抄过来的东西（落地记录）

对照物：`_repos/box4magisk`（v5.2pre, 6ff4307）、`_repos/Surfing-v7`（v7.8.4, 718362c）。
原则：**抄机制，不照搬形态** —— 模块是 root 常驻 shell，本应用有 Android 原生 API 与前台服务，
能用系统回调的地方不引入 inotifyd 与轮询。

## 一、本轮抄了（已实现并有回归测试）

| # | 模块里的做法 | 本应用怎么落地 | 代码 |
|---|---|---|---|
| 1 | `inotifyd` 监听 `/data/misc/net` 的写事件当网络变化信号（源码注释：`/proc` 不支持 inotify，轮询是坏方案） | 等价物：`NetworkCallback` 补挂 `onLinkPropertiesChanged` / `onCapabilitiesChanged`；判据从"接口名"升级为 **接口+本机地址集合**（`tetherKey`），换热点/改 DHCP 段也能触发重装 | [ShareService.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/service/ShareService.kt) · [TetherManager.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/tether/TetherManager.kt) |
| 2 | 防回环放行"重算本机地址集合"（`ip -4 a` 逐条地址放行，且**故意不覆盖 53**） | `tproxy.sh` 在私网段之后追加本机地址放行（nat + mangle），位置排在 DNS 劫持之前 | [tproxy.sh](GuGuGu-clash/app/src/main/assets/tproxy.sh) |
| 3 | 幂等：先 `-C` 查再 `-A`（box4 裸 `-A` 会累积重复规则，是反面教材） | 新增的本机地址放行全部 `-C \|\| -A` | tproxy.sh |
| 4 | 事件去抖：2 秒规则锁、15 秒清连接锁、开机 60 秒稳定期（`BOOT_STABLE_FLAG`） | `autoApplyAllowed()`：5 秒内只自动装一次；开机 60 秒内不自动装（`startAll`/自愈/手动刷新不受限）；被拦下时**不刷新标识**，交给 60 秒巡检补 | ShareService.kt |
| 5 | 切网后 `DELETE /connections` | 新增 `CoreApi.closeConnections()`，在"接口+地址"变化并重装规则后调用 | [CoreApi.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/core/CoreApi.kt) |
| 6 | stop 时"全链遍历清理"（Surfing v7 `destroy_all_rules`），且接口已消失时靠候选名单兜底 | `hs_unapply` 先扫当前有地址的接口，再扫候选名单 `$HS_IFACES`；`Emergency`/`recover.sh` 同样动态枚举 ∪ 候选名单 | tproxy.sh · [Emergency.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/tether/Emergency.kt) |
| 7 | `settings put global private_dns_mode off`（并备份恢复） | 新增 [PrivateDns.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/tether/PrivateDns.kt)：开关由 `managePrivateDns`（默认开）控制，记原值、停止时只在原值不是 off 时恢复；界面在「网络 → 开关 → 专用 DNS」 | PrivateDns.kt · [Prefs.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/prefs/Prefs.kt) · [NetworkActivity.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/ui/NetworkActivity.kt) |
| 8 | 候选接口名单在规则脚本 / 紧急恢复 / 命令行三处各写一份（漂移风险） | 单一真源 `RuleBuilder.CANDIDATE_IFACES`，经 `export HS_IFACES` 注入脚本，`Emergency` 直接引用 | [RuleBuilder.kt](GuGuGu-clash/app/src/main/java/io/guguguclash/tether/RuleBuilder.kt) |

## 二、上一轮（修 bug 时）已经对齐的

| # | 模块做法 | 本应用 |
|---|---|---|
| 9 | 建规则前确认核心活着（`probe_user_group`：pidof + `stat /proc/pid`） | `hs_apply` 先 `pgrep -f 'guguguclash/bin/mihomo'`，没有就拒绝安装；App 侧另有端口齐全校验 |
| 10 | 内核能力用"试插"探测，不读 `/proc/config.gz` | `hs_apply` 里就是试插 TPROXY 失败即回退 |
| 11 | `-w 100` 而不是 `-w 5` | 已统一（含 ip6tables） |
| 12 | 独立守护不依赖 App 存活 | `setsid` + 自写 pid + 不自杀（修 bug 那轮的 P1-2） |

## 三、有意没抄的

| # | 模块做法 | 为什么不抄 |
|---|---|---|
| 13 | 热点侧按客户端 MAC / SSID 自动过滤（box4 `MAC_CHAIN`、Surfing v7 `ctr.inotify`） | 这是产品决策不是技术缺口：本应用已有「已连设备」页手动拉黑（`ClientMonitor.block`）。自动按 MAC 放行/拦截会静默改变别人的网络行为，交给用户点更合适 |
| 14 | 用 `disable` 文件 + inotifyd 实现"开关零重启" | 本应用有前台服务与热重载，语义已覆盖；再引入一层文件协议只会多一个失败点 |
| 15 | 默认开 `BLOCK_QUIC`、`PROXY_IPV6=-1` 关掉整个 v6 栈、ipset 灌全量 CN 网段 | 都是全局副作用大的动作。本应用的做法是按需（`blockIpv6` 是 FORWARD 层阻断，不动系统 v6 配置） |
| 16 | 接口名写死（box4 默认 `HOTSPOT_INTERFACE="wlan2"`）、`"$iptables"` 整条命令塞变量、单文件 1900 行 | 反面教材，不抄 |

## 四、验证

- 单测：`23 suites / 201 tests / 0 failures`（本轮新增 6 个：本机地址放行顺序与幂等、unapply 扫候选名单、`tetherKey` 网段可见性、`HS_IFACES` 注入、专用 DNS 恢复判定 3 项）
- 打包：`:app:assembleArm64Debug` 通过
- 命令：
  ```powershell
  $env:GRADLE_USER_HOME='D:\Agent\workplace\Ordinary\build-env\gradle-home'
  $env:ANDROID_HOME='D:\Agent\workplace\Ordinary\build-env\sdk'
  $env:ANDROID_USER_HOME='D:\Agent\workplace\Ordinary\build-env\android-home'
  & '...\gradle-8.7\bin\gradle.bat' --offline :app:testArm64DebugUnitTest --rerun
  ```

## 五、设备侧仍需复验

1. 换热点（或改 DHCP 段）后，日志应出现「共享接口/网段变化：wlan2|旧地址 -> wlan2|新地址」，随后「已断开内核里的旧连接」。
2. 打开手机「私人 DNS」再开共享，日志应出现「已关闭系统专用 DNS（原值 opportunistic…）」；停止共享后应恢复。
3. 开机后 60 秒内的网络事件应被「未过稳定期」拦下，由 60 秒巡检补装。
