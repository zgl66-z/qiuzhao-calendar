#!/usr/bin/env bash
# 构建 → 安装 → 逐页签截图（当前主用的真机验证脚本）
set -uo pipefail
cd "$(dirname "$0")"

# 本机覆盖（local.env 已在 .gitignore 中）
if [ -f local.env ]; then . ./local.env; fi

# adb 默认从 PATH 取；本机若没加进 PATH，用 ADB=/path/to/adb 覆盖
ADB="${ADB:-adb}"
APK=别错过.apk

need_device() {
  if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "  !! 设备不在线，尝试重连"
    "$ADB" reconnect offline >/dev/null 2>&1
    sleep 3
  fi
}

echo "== 1. 构建 =="
bash build.sh 2>&1 | grep -viE "WARNING|警告|已过时|^注:|options|Binary" | tail -3
[ -f "$APK" ] || { echo "  !! 构建失败（没有产物），中止"; exit 1; }

echo "== 2. 唤醒 + 设常亮（防息屏断连）=="
need_device
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
sleep 1
"$ADB" shell input swipe 630 2200 630 700 300 >/dev/null 2>&1
sleep 1
"$ADB" shell svc power stayon true >/dev/null 2>&1

echo "== 3. 安装 =="
need_device
cp "$APK" install-me.apk
"$ADB" install -r install-me.apk 2>&1 | tail -1 | sed 's/^/  /'
"$ADB" shell dumpsys package com.qiuzhao.alarm 2>&1 | grep lastUpdateTime | sed 's/^/  /'

echo "== 4. 启动 =="
"$ADB" shell am force-stop com.qiuzhao.alarm
"$ADB" shell am start -n com.qiuzhao.alarm/.MainActivity >/dev/null 2>&1
sleep 4

shot() {
  MSYS_NO_PATHCONV=1 "$ADB" shell screencap -p /sdcard/s.png >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/s.png "$1" 2>&1 | tail -1
}

nav_xy() {
  rm -f u.xml
  MSYS_NO_PATHCONV=1 "$ADB" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 "$ADB" pull /sdcard/u.xml u.xml >/dev/null 2>&1
  python -c "
import io,re,sys,os
p='u.xml'
if not os.path.exists(p): raise SystemExit
t=io.open(p,encoding='utf-8').read()
ms=re.findall(r'text=\"'+re.escape(sys.argv[1])+r'\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',t)
if ms:
    x1,y1,x2,y2=map(int,ms[-1]); print('%d %d'%((x1+x2)//2,(y1+y2)//2))
" "$1"
}

echo "== 5. 三个页签截图 =="
shot tab_events.png
for pair in "添加:tab_add.png" "我的:tab_mine.png" "事件:tab_events2.png"; do
  label="${pair%%:*}"; out="${pair##*:}"
  xy=$(nav_xy "$label" | tail -1)
  echo "  [$label] -> ${xy:-未找到}"
  if [ -n "$xy" ]; then "$ADB" shell input tap $xy; sleep 3; shot "$out"; fi
done

echo "== 6. 崩溃检查 =="
"$ADB" logcat -d -s AndroidRuntime:E 2>&1 | tail -4

rm -f install-me.apk u.xml

# 可选：把产物复制到别处（本机可在 local.env 里设 DELIVER_TO）
if [ -n "${DELIVER_TO:-}" ]; then
  cp "$APK" "$DELIVER_TO/" && echo "  已复制到 $DELIVER_TO"
fi
echo "  产物：$APK"
