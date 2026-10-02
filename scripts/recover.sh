#!/system/bin/sh
# VpnShare 紧急恢复：电脑突然上不了网时执行这个。
# 用法：su -c 'sh /data/local/tmp/vpnshare-recover.sh'
# 作用：摘掉所有透明代理规则，让热点客户端回到纯直连。不动内核、不动 App。
echo '[1/3] 摘除 iptables 跳转'
for ifc in wlan2 wlan1 ap0 softap0 swlan0 rndis0 usb0 eth0 bt-pan; do
  iptables -w 5 -t nat -D PREROUTING -i $ifc -j HS_NAT 2>/dev/null
  iptables -w 5 -t mangle -D PREROUTING -i $ifc -j HS_MANGLE 2>/dev/null
  iptables -w 5 -D FORWARD -i $ifc -j HS_BLOCK 2>/dev/null
  ip6tables -D FORWARD -i $ifc -j DROP 2>/dev/null
done
echo '[2/3] 销毁自定义链与路由表'
iptables -w 5 -F HS_BLOCK 2>/dev/null; iptables -w 5 -X HS_BLOCK 2>/dev/null
iptables -w 5 -t nat -F HS_NAT 2>/dev/null; iptables -w 5 -t nat -X HS_NAT 2>/dev/null
iptables -w 5 -t mangle -F HS_MANGLE 2>/dev/null; iptables -w 5 -t mangle -X HS_MANGLE 2>/dev/null
ip rule del fwmark 2025 lookup 100 2>/dev/null
ip route flush table 100 2>/dev/null
pkill -f 'tproxy.sh watch' 2>/dev/null
echo '[3/3] 剩余规则：'
iptables -t nat -S PREROUTING
echo '完成。电脑应已恢复直连；要恢复代理请在 App 里重新打开共享开关。'