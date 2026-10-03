#!/system/bin/sh
# GuGuGu-clash 紧急恢复：电脑突然上不了网时执行这个。
# 用法：su -c 'sh /data/local/tmp/guguguclash-recover.sh'
# 作用：摘掉所有透明代理规则，让热点客户端回到纯直连。不动内核、不动 App。
#
# 优先调用 App 落盘的那份接管脚本（规则逻辑的唯一真源，mark/端口都随上次 apply 走）；
# 只有它不存在时才退回下面的内联清扫。

SCRIPT=/data/adb/guguguclash/run/tproxy.sh

echo '[1/4] 停止独立守护'
pkill -f 'tproxy.sh watch' 2>/dev/null

echo '[2/4] 摘除跳转与自定义链'
if [ -f "$SCRIPT" ]; then
  echo "  调用 $SCRIPT off"
  sh "$SCRIPT" off
else
  echo '  未找到接管脚本，使用内联清扫'
  # ① 当前真实存在的接口
  for ifc in $(ip -o link show 2>/dev/null | awk -F': ' '{print $2}' | cut -d'@' -f1); do
    [ "$ifc" = "lo" ] && continue
    iptables -w 100 -t nat -D PREROUTING -i "$ifc" -j HS_NAT 2>/dev/null
    iptables -w 100 -t mangle -D PREROUTING -i "$ifc" -j HS_MANGLE 2>/dev/null
    iptables -w 100 -D FORWARD -i "$ifc" -j HS_BLOCK 2>/dev/null
    ip6tables -w 100 -D FORWARD -i "$ifc" -j DROP 2>/dev/null
  done
  # ② 已经消失的接口只能靠名单
  for ifc in wlan0 wlan1 wlan2 wlan3 ap0 ap1 ap2 softap0 softap1 swlan0 swlan1 \
             rndis0 rndis1 usb0 usb1 eth0 eth1 bt-pan; do
    iptables -w 100 -t nat -D PREROUTING -i "$ifc" -j HS_NAT 2>/dev/null
    iptables -w 100 -t mangle -D PREROUTING -i "$ifc" -j HS_MANGLE 2>/dev/null
    iptables -w 100 -D FORWARD -i "$ifc" -j HS_BLOCK 2>/dev/null
    ip6tables -w 100 -D FORWARD -i "$ifc" -j DROP 2>/dev/null
  done
  iptables -w 100 -F HS_BLOCK 2>/dev/null; iptables -w 100 -X HS_BLOCK 2>/dev/null
  iptables -w 100 -t nat -F HS_NAT 2>/dev/null; iptables -w 100 -t nat -X HS_NAT 2>/dev/null
  iptables -w 100 -t mangle -F HS_MANGLE 2>/dev/null; iptables -w 100 -t mangle -X HS_MANGLE 2>/dev/null
  ip rule del fwmark 2025 lookup 100 2>/dev/null
  ip route flush table 100 2>/dev/null
fi

echo '[3/4] 剩余规则：'
iptables -t nat -S PREROUTING 2>/dev/null
echo '[4/4] 完成。电脑应已恢复直连；要恢复代理请在 App 里重新打开共享开关。'
