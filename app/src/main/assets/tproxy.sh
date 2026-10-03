#!/system/bin/sh
# GuGuGu-clash 接管脚本：把热点 / USB 共享的客户端流量透明代理到本机内核
# 独立用法: su -c 'sh /data/adb/guguguclash/run/tproxy.sh on|off|status'
# App 用法: App 把装配好的脚本落盘到 /data/adb/guguguclash/run/tproxy.sh，再执行 sh <路径> <动作>
#           **必须落盘执行**：下面的独立守护要用 $0 把自己重新拉起来；
#           把脚本文本灌进 su 的 stdin 时 $0 是 shell 名，守护根本起不来（旧实现在此静默失效）。
# 环境变量: HS_ACTION=on|off|status  HS_IFACE=(留空=自动)  HS_REDIR=7892  HS_TPROXY=7893  HS_DNS=1053  HS_UDP=1  HS_BLOCK_V6=1

[ -n "$1" ] && HS_ACTION="$1"
[ -z "$HS_ACTION" ] && HS_ACTION=status
[ -z "$HS_REDIR" ] && HS_REDIR=7892
[ -z "$HS_TPROXY" ] && HS_TPROXY=7893
[ -z "$HS_DNS" ] && HS_DNS=1053
[ -z "$HS_UDP" ] && HS_UDP=1
[ -z "$HS_BLOCK_V6" ] && HS_BLOCK_V6=1
# 共享接口候选名单（清理用）。App 侧由 RuleBuilder 注入同一份名单。
[ -z "$HS_IFACES" ] && HS_IFACES="wlan0 wlan1 wlan2 wlan3 ap0 ap1 ap2 softap0 softap1 swlan0 swlan1 rndis0 rndis1 usb0 usb1 eth0 eth1 bt-pan"

HS_NAT=HS_NAT
HS_MANGLE=HS_MANGLE
HS_BLOCK=HS_BLOCK
# mark / 表号：App 侧由 RuleBuilder 注入（唯一真源），命令行下用这里的默认值
[ -z "$HS_TABLE" ] && HS_TABLE=100
# 必须与内核 tun.routing-mark（配置里是 2024）不同：ip rule 是全局的，
# 撞号会把内核自己 socket 打上的 mark 也路由进 table 100（local），
# 导致内核出站流量全部回环，表现为「一装规则电脑就断网」。
[ -z "$HS_MARK" ] && HS_MARK=2025
# 守护进程 pid 文件。放 /data/local/tmp 是因为它必须在 App 被杀后仍然可读写
HS_WATCH_PID=/data/local/tmp/guguguclash.watch.pid
# 脚本自身路径（必须是绝对路径）。独立守护靠它重启自己。
HS_SELF="$0"
case "$HS_SELF" in
  /*) ;;
  *) HS_SELF="" ;;
esac
HS_NETS="0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4"

hs_default_dev() {
  ip route show default 2>/dev/null | awk '/default/ {for(i=1;i<=NF;i++) if($i=="dev") {print $(i+1); exit}}'
}

hs_detect_iface() {
  if [ -n "$HS_IFACE" ]; then echo "$HS_IFACE"; return 0; fi
  DEF=$(hs_default_dev)
  BEST=""
  for ifc in $(ip -o -4 addr show 2>/dev/null | awk '{print $2}' | tr -d ':' | sort -u); do
    [ "$ifc" = "lo" ] && continue
    [ "$ifc" = "$DEF" ] && continue
    case "$ifc" in ap*|wlan*|softap*|swlan*|rndis*|usb*|bt-pan*|eth*) ;; *) continue ;; esac
    for cidr in $(ip -o -4 addr show dev "$ifc" 2>/dev/null | awk '{print $4}'); do
      ipa=${cidr%%/*}
      case "$ipa" in 192.168.*|10.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) ;; *) continue ;; esac
      case "$ipa" in *.*.*.1) echo "$ifc"; return 0 ;; esac
      [ -z "$BEST" ] && BEST="$ifc"
    done
  done
  [ -n "$BEST" ] && { echo "$BEST"; return 0; }
  return 1
}

#
# 独立守护：内核进程一消失就自动摘掉规则。
#
# 为什么必须有：App 自己的看门狗跑在 App 进程里，一旦被 force-stop（或崩溃、或被输入法
# 之类的内存压力干掉），看门狗跟着死，而 iptables 规则**留在内核里**。此时热点客户端的
# 流量仍然被丢向一个已经不存在的代理端口 —— 症状就是电脑突然完全上不了网，
# 而手机上什么提示都没有。实测反复踩过。
#
# 这个守护是 root 起的独立进程，不依赖 App、不依赖 su 会话，因此能兜住这种情况。
hs_watch_stop() {
  if [ -f "$HS_WATCH_PID" ]; then
    pid=$(cat "$HS_WATCH_PID" 2>/dev/null)
    # 绝不杀自己：守护在检出异常时会走到这里，pid 文件里就是它
    [ -n "$pid" ] && [ "$pid" != "$$" ] && kill "$pid" 2>/dev/null
    rm -f "$HS_WATCH_PID"
  fi
}

hs_watch_run() {
  # 自己写 pid 文件。不能用父进程的 $!：setsid 在需要时会 fork，
  # $! 可能指向一个已经退出的中间进程 —— 那样 stop 杀不掉守护（越积越多），
  # 或者更糟：pid 恰好是守护自己，清理时把自己杀掉，规则残留。
  echo $$ > "$HS_WATCH_PID" 2>/dev/null
  while true; do
    sleep 4
    # 用全路径匹配，避免误判用户自己跑的其它 mihomo（例如 CMFA）
    if ! pgrep -f 'guguguclash/bin/mihomo' >/dev/null 2>&1; then
      echo "WATCH 内核已不在，自动摘除共享规则"
      hs_unapply keep-watch
      rm -f "$HS_WATCH_PID"
      exit 0
    fi
  done
}

hs_watch_start() {
  hs_watch_stop
  if [ -z "$HS_SELF" ]; then
    echo "WARN 脚本不是以文件路径执行的（\$0=$0），独立守护无法启动；规则仍由 App 侧巡检兜底"
    return 0
  fi
  rm -f "$HS_WATCH_PID"
  # 必须用 setsid（不是 nohup）：
  # nohup 只忽略 SIGHUP，进程仍在 App 的进程组里；android 的 force-stop 是按
  # 进程组 SIGKILL 的，守护会被一起带走 —— 实测就是这样，规则留在内核里没人清。
  # setsid 让守护进入独立会话，force-stop 够不着（已实测：强杀 App 后仍存活）。
  setsid sh "$HS_SELF" watch >/dev/null 2>&1 </dev/null &
  # 等守护自己把 pid 写进去，确认它真的起来了（失败也不阻断 apply）
  n=0
  while [ $n -lt 3 ] && [ ! -f "$HS_WATCH_PID" ]; do sleep 1; n=$((n + 1)); done
  if [ -f "$HS_WATCH_PID" ]; then
    echo "守护已启动 (pid $(cat "$HS_WATCH_PID"))"
  else
    echo "WARN 独立守护未确认启动，规则仍由 App 侧巡检兜底"
  fi
}

hs_apply() {
  IFACE=$(hs_detect_iface)
  if [ -z "$IFACE" ]; then echo "ERR 未找到热点/USB 共享接口"; return 1; fi
  echo "IFACE=$IFACE REDIR=$HS_REDIR TPROXY=$HS_TPROXY DNS=$HS_DNS UDP=$HS_UDP V6=$HS_BLOCK_V6"

  # 装规则前必须确认内核真的活着：否则客户端流量会被 REDIRECT 到一个不存在的端口，
  # 表现为「一开共享电脑就断网」。App 侧另有「端口是否都在监听」的校验，
  # 这条是给命令行用法兜底（也是照 box4/Surfing 的 probe_user_group 思路补的）。
  if ! pgrep -f 'guguguclash/bin/mihomo' >/dev/null 2>&1; then
    echo "ERR 内核未在运行，已拒绝安装规则（先在内核页启动内核）"
    return 1
  fi

  iptables -w 100 -t nat -N $HS_NAT 2>/dev/null
  iptables -w 100 -t nat -F $HS_NAT 2>/dev/null
  # ① DNS 劫持必须排在**所有放行规则之前**（实测踩过大坑）：
  #    客户端的解析请求打的是热点网关地址（10.123.89.39 这种），而网关必然落在
  #    下面「私网放行」覆盖的 10.0.0.0/8 / 192.168.0.0/16 里。放行规则一旦排在前面，
  #    客户端的 DNS 就绕过内核 —— 内核从没见过这次解析，也就拿不到域名，
  #    共享出去的流量只能按 IP 分流：域名规则、广告拦截、机场自带的分组全部失效。
  #    实测证据：电脑访问 www.github.com，内核里 match 到的是最后那条 MATCH，
  #    metadata.host 为空、chains 走到「漏网之鱼」—— 说明域名信息从未进入内核。
  iptables -w 100 -t nat -A $HS_NAT -p udp --dport 53 -j REDIRECT --to-ports "$HS_DNS" 2>/dev/null
  iptables -w 100 -t nat -A $HS_NAT -p tcp --dport 53 -j REDIRECT --to-ports "$HS_DNS" 2>/dev/null
  # ② 私网放行
  for n in $HS_NETS; do iptables -w 100 -t nat -A $HS_NAT -d "$n" -j RETURN; done
  # 本机真实地址也一律放行（幂等：先 -C 查再 -A）。换热点 / 改 DHCP 段时接口名可能不变、
  # 只有地址变了，把实际地址放行就兜住了非 RFC1918 的共享网段，不必等重新 apply。
  # 注意它同样排在 DNS 劫持之后：本机地址里就有热点网关，DNS 必须先被劫走。
  for ipa in $(ip -4 a 2>/dev/null | awk '/inet/ {print $2}'); do
    iptables -w 100 -t nat -C $HS_NAT -d "$ipa" -j RETURN 2>/dev/null || \
      iptables -w 100 -t nat -A $HS_NAT -d "$ipa" -j RETURN
  done
  iptables -w 100 -t nat -A $HS_NAT -p tcp -j REDIRECT --to-ports "$HS_REDIR" || { echo "ERR 写入 nat 规则失败"; return 1; }
  iptables -w 100 -t nat -D PREROUTING -i "$IFACE" -j $HS_NAT 2>/dev/null
  iptables -w 100 -t nat -I PREROUTING -i "$IFACE" -j $HS_NAT
  echo "TCP + DNS 规则 OK"

  if [ "$HS_UDP" = "1" ]; then
    iptables -w 100 -t mangle -N $HS_MANGLE 2>/dev/null
    iptables -w 100 -t mangle -F $HS_MANGLE 2>/dev/null
    for n in $HS_NETS; do iptables -w 100 -t mangle -A $HS_MANGLE -d "$n" -j RETURN; done
    # 与 nat 链同理：本机地址放行，且排在 53 放行之前
    for ipa in $(ip -4 a 2>/dev/null | awk '/inet/ {print $2}'); do
      iptables -w 100 -t mangle -C $HS_MANGLE -d "$ipa" -j RETURN 2>/dev/null || \
        iptables -w 100 -t mangle -A $HS_MANGLE -d "$ipa" -j RETURN
    done
    # UDP 53 显式放行、**故意不做 TPROXY**：DNS 已经由上面 nat 链的 53→REDIRECT 交给内核 DNS 口（$HS_DNS）。
    # 若这里也让 53 走 TPROXY，查询会被劫到代理入口（$HS_TPROXY），内核 DNS 收不到 → fake-ip 失效。
    # （两处「不一致」是分工：nat 决定谁来做 DNS，mangle 决定哪些 UDP 去代理。）
    # 别为了「和 nat 链对齐」把这条后置或删掉 —— RuleScriptTest.udp53IsExcludedFromTproxy 钉着它。
    iptables -w 100 -t mangle -A $HS_MANGLE -p udp --dport 53 -j RETURN
    if iptables -w 100 -t mangle -A $HS_MANGLE -p udp -j TPROXY --on-port "$HS_TPROXY" --tproxy-mark $HS_MARK 2>/dev/null; then
      iptables -w 100 -t mangle -D PREROUTING -i "$IFACE" -j $HS_MANGLE 2>/dev/null
      iptables -w 100 -t mangle -I PREROUTING -i "$IFACE" -j $HS_MANGLE
      ip rule del fwmark $HS_MARK lookup $HS_TABLE 2>/dev/null
      ip rule add fwmark $HS_MARK lookup $HS_TABLE
      ip route flush table $HS_TABLE 2>/dev/null
      ip route add local 0.0.0.0/0 dev lo table $HS_TABLE
      echo "UDP(TPROXY) 规则 OK"
    else
      iptables -w 100 -t mangle -F $HS_MANGLE 2>/dev/null
      iptables -w 100 -t mangle -X $HS_MANGLE 2>/dev/null
      echo "WARN 内核不支持 xt_TPROXY，UDP 未被代理"
    fi
  fi

  # 客户端屏蔽链（默认空，App 往里面加 DROP 规则）
  iptables -w 100 -N $HS_BLOCK 2>/dev/null
  iptables -w 100 -D FORWARD -i "$IFACE" -j $HS_BLOCK 2>/dev/null
  iptables -w 100 -I FORWARD -i "$IFACE" -j $HS_BLOCK

  if [ "$HS_BLOCK_V6" = "1" ]; then
    ip6tables -w 100 -D FORWARD -i "$IFACE" -j DROP 2>/dev/null
    ip6tables -w 100 -I FORWARD -i "$IFACE" -j DROP 2>/dev/null && echo "IPv6 泄漏已阻断"
  fi

  hs_watch_start
  echo "OK 已启用共享"
}

hs_unapply() {
  # 参数 "keep-watch" = 由守护自己调用：这时绝不能 kill 自己，
  # 否则清理做到一半进程就没了，规则残留 —— 正是守护要防的那件事。
  [ "$1" != "keep-watch" ] && hs_watch_stop
  # ① 当前真的有地址的接口
  for ifc in $(ip -o -4 addr show 2>/dev/null | awk '{print $2}' | tr -d ':' | sort -u); do
    iptables -w 100 -t nat -D PREROUTING -i "$ifc" -j $HS_NAT 2>/dev/null
    iptables -w 100 -t mangle -D PREROUTING -i "$ifc" -j $HS_MANGLE 2>/dev/null
    iptables -w 100 -D FORWARD -i "$ifc" -j $HS_BLOCK 2>/dev/null
    ip6tables -w 100 -D FORWARD -i "$ifc" -j DROP 2>/dev/null
  done
  # ② 接口已经消失时（热点关掉 / USB 拔掉 / 蓝牙断开）规则仍挂在那个接口上，
  #    只有不依赖当前接口状态的候选名单能摘到 —— 漏掉就会留下一条指向空链的跳转，
  #    下次该接口再出现时客户端会先被丢进一个不存在的代理端口。
  for ifc in $HS_IFACES; do
    iptables -w 100 -t nat -D PREROUTING -i "$ifc" -j $HS_NAT 2>/dev/null
    iptables -w 100 -t mangle -D PREROUTING -i "$ifc" -j $HS_MANGLE 2>/dev/null
    iptables -w 100 -D FORWARD -i "$ifc" -j $HS_BLOCK 2>/dev/null
    ip6tables -w 100 -D FORWARD -i "$ifc" -j DROP 2>/dev/null
  done
  iptables -w 100 -F $HS_BLOCK 2>/dev/null; iptables -X $HS_BLOCK 2>/dev/null
  iptables -w 100 -t nat -F $HS_NAT 2>/dev/null;    iptables -t nat -X $HS_NAT 2>/dev/null
  iptables -w 100 -t mangle -F $HS_MANGLE 2>/dev/null; iptables -t mangle -X $HS_MANGLE 2>/dev/null
  ip rule del fwmark $HS_MARK lookup $HS_TABLE 2>/dev/null
  ip route flush table $HS_TABLE 2>/dev/null
  echo "OK 已停用共享"
}

hs_status() {
  echo "--- root ---"; id 2>/dev/null | head -n1
  echo "--- 本机网卡 ---"; ip -o -4 addr show 2>/dev/null | awk '{print "  " $2 " " $4}' | tr -d ':'
  echo "--- 默认路由 ---"; echo "  $(hs_default_dev)"
  echo "--- 共享接口(自动检测) ---"; echo "  $(hs_detect_iface || echo none)"
  echo "--- nat 链 ---";    iptables -w 100 -t nat -S $HS_NAT 2>/dev/null || echo "  不存在"
  echo "--- mangle 链 ---"; iptables -w 100 -t mangle -S $HS_MANGLE 2>/dev/null || echo "  不存在"
  echo "--- ip rule ---";   ip rule show 2>/dev/null | grep "fwmark $HS_MARK" || echo "  不存在"
  echo "--- 代理端口监听 ---"
  (netstat -ltn 2>/dev/null || ss -ltn 2>/dev/null) | grep -E ":$HS_REDIR|:$HS_TPROXY|:$HS_DNS|:7890" || echo "  未发现内核监听端口"
}

case "$HS_ACTION" in
  on)     hs_apply ;;
  off)    hs_unapply ;;
  watch)  hs_watch_run ;;
  status) hs_status ;;
  *) echo "用法: sh tproxy.sh on|off|status"; exit 1 ;;
esac
