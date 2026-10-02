#!/system/bin/sh
# VpnShare 接管脚本：把热点 / USB 共享的客户端流量透明代理到本机内核
# 独立用法: su -c 'sh tproxy.sh on|off|status'
# App 用法: 注入 HS_* 环境变量后经 su 执行（同一份脚本）
# 环境变量: HS_ACTION=on|off|status  HS_IFACE=(留空=自动)  HS_REDIR=7892  HS_TPROXY=7893  HS_DNS=1053  HS_UDP=1  HS_BLOCK_V6=1

[ -n "$1" ] && HS_ACTION="$1"
[ -z "$HS_ACTION" ] && HS_ACTION=status
[ -z "$HS_REDIR" ] && HS_REDIR=7892
[ -z "$HS_TPROXY" ] && HS_TPROXY=7893
[ -z "$HS_DNS" ] && HS_DNS=1053
[ -z "$HS_UDP" ] && HS_UDP=1
[ -z "$HS_BLOCK_V6" ] && HS_BLOCK_V6=1

HS_NAT=HS_NAT
HS_MANGLE=HS_MANGLE
HS_BLOCK=HS_BLOCK
HS_TABLE=100
# 必须与内核 tun.routing-mark（配置里是 2024）不同：ip rule 是全局的，
# 撞号会把内核自己 socket 打上的 mark 也路由进 table 100（local），
# 导致内核出站流量全部回环，表现为「一装规则电脑就断网」。
HS_MARK=2025
# 守护进程 pid 文件。放 /data/local/tmp 是因为它必须在 App 被杀后仍然可读写
HS_WATCH_PID=/data/local/tmp/vpnshare.watch.pid
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
    kill "$(cat "$HS_WATCH_PID" 2>/dev/null)" 2>/dev/null
    rm -f "$HS_WATCH_PID"
  fi
}

hs_watch_run() {
  while true; do
    sleep 4
    # 用全路径匹配，避免误判用户自己跑的其它 mihomo（例如 CMFA）
    if ! pgrep -f 'vpnshare/bin/mihomo' >/dev/null 2>&1; then
      echo "WATCH 内核已不在，自动摘除共享规则"
      hs_unapply
      rm -f "$HS_WATCH_PID"
      exit 0
    fi
  done
}

hs_watch_start() {
  hs_watch_stop
  # 必须用 setsid（不是 nohup）：
  # nohup 只忽略 SIGHUP，进程仍在 App 的进程组里；android 的 force-stop 是按
  # 进程组 SIGKILL 的，守护会被一起带走 —— 实测就是这样，规则留在内核里没人清。
  # setsid 让守护进入独立会话，force-stop 够不着（已实测：强杀 App 后仍存活）。
  setsid sh "$0" watch >/dev/null 2>&1 </dev/null &
  echo $! > "$HS_WATCH_PID" 2>/dev/null
}

hs_apply() {
  IFACE=$(hs_detect_iface)
  if [ -z "$IFACE" ]; then echo "ERR 未找到热点/USB 共享接口"; return 1; fi
  echo "IFACE=$IFACE REDIR=$HS_REDIR TPROXY=$HS_TPROXY DNS=$HS_DNS UDP=$HS_UDP V6=$HS_BLOCK_V6"

  iptables -w 5 -t nat -N $HS_NAT 2>/dev/null
  iptables -w 5 -t nat -F $HS_NAT 2>/dev/null
  for n in $HS_NETS; do iptables -w 5 -t nat -A $HS_NAT -d "$n" -j RETURN; done
  iptables -w 5 -t nat -A $HS_NAT -p udp --dport 53 -j REDIRECT --to-ports "$HS_DNS" 2>/dev/null
  iptables -w 5 -t nat -A $HS_NAT -p tcp --dport 53 -j REDIRECT --to-ports "$HS_DNS" 2>/dev/null
  iptables -w 5 -t nat -A $HS_NAT -p tcp -j REDIRECT --to-ports "$HS_REDIR" || { echo "ERR 写入 nat 规则失败"; return 1; }
  iptables -w 5 -t nat -D PREROUTING -i "$IFACE" -j $HS_NAT 2>/dev/null
  iptables -w 5 -t nat -I PREROUTING -i "$IFACE" -j $HS_NAT
  echo "TCP + DNS 规则 OK"

  if [ "$HS_UDP" = "1" ]; then
    iptables -w 5 -t mangle -N $HS_MANGLE 2>/dev/null
    iptables -w 5 -t mangle -F $HS_MANGLE 2>/dev/null
    for n in $HS_NETS; do iptables -w 5 -t mangle -A $HS_MANGLE -d "$n" -j RETURN; done
    iptables -w 5 -t mangle -A $HS_MANGLE -p udp --dport 53 -j RETURN
    if iptables -w 5 -t mangle -A $HS_MANGLE -p udp -j TPROXY --on-port "$HS_TPROXY" --tproxy-mark $HS_MARK 2>/dev/null; then
      iptables -w 5 -t mangle -D PREROUTING -i "$IFACE" -j $HS_MANGLE 2>/dev/null
      iptables -w 5 -t mangle -I PREROUTING -i "$IFACE" -j $HS_MANGLE
      ip rule del fwmark $HS_MARK lookup $HS_TABLE 2>/dev/null
      ip rule add fwmark $HS_MARK lookup $HS_TABLE
      ip route flush table $HS_TABLE 2>/dev/null
      ip route add local 0.0.0.0/0 dev lo table $HS_TABLE
      echo "UDP(TPROXY) 规则 OK"
    else
      iptables -w 5 -t mangle -F $HS_MANGLE 2>/dev/null
      iptables -w 5 -t mangle -X $HS_MANGLE 2>/dev/null
      echo "WARN 内核不支持 xt_TPROXY，UDP 未被代理"
    fi
  fi

  # 客户端屏蔽链（默认空，App 往里面加 DROP 规则）
  iptables -w 5 -N $HS_BLOCK 2>/dev/null
  iptables -w 5 -D FORWARD -i "$IFACE" -j $HS_BLOCK 2>/dev/null
  iptables -w 5 -I FORWARD -i "$IFACE" -j $HS_BLOCK

  if [ "$HS_BLOCK_V6" = "1" ]; then
    ip6tables -D FORWARD -i "$IFACE" -j DROP 2>/dev/null
    ip6tables -I FORWARD -i "$IFACE" -j DROP 2>/dev/null && echo "IPv6 泄漏已阻断"
  fi

  hs_watch_start
  echo "OK 已启用共享"
}

hs_unapply() {
  hs_watch_stop
  for ifc in $(ip -o -4 addr show 2>/dev/null | awk '{print $2}' | tr -d ':' | sort -u); do
    iptables -w 5 -t nat -D PREROUTING -i "$ifc" -j $HS_NAT 2>/dev/null
    iptables -w 5 -t mangle -D PREROUTING -i "$ifc" -j $HS_MANGLE 2>/dev/null
    iptables -w 5 -D FORWARD -i "$ifc" -j $HS_BLOCK 2>/dev/null
    ip6tables -D FORWARD -i "$ifc" -j DROP 2>/dev/null
  done
  iptables -w 5 -F $HS_BLOCK 2>/dev/null; iptables -X $HS_BLOCK 2>/dev/null
  iptables -w 5 -t nat -F $HS_NAT 2>/dev/null;    iptables -t nat -X $HS_NAT 2>/dev/null
  iptables -w 5 -t mangle -F $HS_MANGLE 2>/dev/null; iptables -t mangle -X $HS_MANGLE 2>/dev/null
  ip rule del fwmark $HS_MARK lookup $HS_TABLE 2>/dev/null
  ip route flush table $HS_TABLE 2>/dev/null
  echo "OK 已停用共享"
}

hs_status() {
  echo "--- root ---"; id 2>/dev/null | head -n1
  echo "--- 本机网卡 ---"; ip -o -4 addr show 2>/dev/null | awk '{print "  " $2 " " $4}' | tr -d ':'
  echo "--- 默认路由 ---"; echo "  $(hs_default_dev)"
  echo "--- 共享接口(自动检测) ---"; echo "  $(hs_detect_iface || echo none)"
  echo "--- nat 链 ---";    iptables -w 5 -t nat -S $HS_NAT 2>/dev/null || echo "  不存在"
  echo "--- mangle 链 ---"; iptables -w 5 -t mangle -S $HS_MANGLE 2>/dev/null || echo "  不存在"
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
