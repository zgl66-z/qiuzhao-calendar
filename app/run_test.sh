#!/usr/bin/env bash
# 离线验证 ICS 解析逻辑：在普通 JVM 上跑 Sched 的纯函数（解析部分不碰 android.*），不需要手机。
#
# 样本取自 test/sample.ics；若不存在则生成一份合成样本，
# 覆盖本项目实际用到的各种写法：单次事件 + P6D/P1D 双提醒、工作日重复 + PT5M、
# 仅有日期的 DTSTART、组合时长 P1DT2H30M 等。克隆下来即可自证解析正确。
#
# 需要 Android SDK（platforms;android-35 与 build-tools;35.0.0）：
#   因为要先用 aapt2 生成 R.java，否则全量编译 src 会找不到 R 类。
set -euo pipefail
cd "$(dirname "$0")"

# 本机覆盖（local.env 已在 .gitignore 中，不会进仓库）
if [ -f local.env ]; then . ./local.env; fi

find_sdk() {
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
           "$LOCALAPPDATA/Android/Sdk" "$HOME/Android/Sdk" \
           "$HOME/Library/Android/sdk" "/usr/lib/android-sdk"; do
    if [ -n "$c" ] && [ -f "$c/platforms/android-35/android.jar" ]; then
      echo "$c"
      return 0
    fi
  done
  return 1
}

SDK=$(find_sdk) || {
  echo "找不到 Android SDK（需要 platforms;android-35 与 build-tools;35.0.0）。"
  echo "请设置环境变量 ANDROID_SDK_ROOT，或在本目录建一个 local.env 写入："
  echo "    ANDROID_SDK_ROOT=/path/to/android-sdk"
  exit 1
}
BT="$SDK/build-tools/35.0.0"
AJAR="$SDK/platforms/android-35/android.jar"

# 工具路径：优先无后缀（类 Unix），其次 .exe / .bat（Windows）
pick() {
  for c in "$1/$2" "$1/$2.exe" "$1/$2.bat"; do
    if [ -f "$c" ]; then echo "$c"; return 0; fi
  done
  echo "找不到 $2（在 $1 下）" >&2
  return 1
}
AAPT2=$(pick "$BT" aapt2)

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

if [ ! -f test/sample.ics ]; then
  echo "== 生成合成样本 test/sample.ics =="
  cat > test/sample.ics <<'ICS'
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//sample//test//CN

BEGIN:VEVENT
UID:one-off@sample
SUMMARY:单次事件（提前 6 天 + 提前 1 天）
DTSTART;TZID=Asia/Shanghai:20261015T090000
BEGIN:VALARM
TRIGGER:-P6D
ACTION:DISPLAY
DESCRIPTION:提前6天
END:VALARM
BEGIN:VALARM
TRIGGER:-P1D
ACTION:DISPLAY
DESCRIPTION:提前1天
END:VALARM
END:VEVENT

BEGIN:VEVENT
UID:recurring@sample
SUMMARY:工作日重复（提前 5 分钟）
DTSTART;TZID=Asia/Shanghai:20261001T090000
RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20261231T155959Z
BEGIN:VALARM
TRIGGER:-PT5M
ACTION:DISPLAY
DESCRIPTION:提前5分钟
END:VALARM
END:VEVENT

BEGIN:VEVENT
UID:date-only@sample
SUMMARY:只有日期的 DTSTART（应落在当天 09:00）
DTSTART;VALUE=DATE:20261120
BEGIN:VALARM
TRIGGER:-P1W
ACTION:DISPLAY
DESCRIPTION:提前1周
END:VALARM
END:VEVENT

BEGIN:VEVENT
UID:combined@sample
SUMMARY:组合时长 P1DT2H30M
DTSTART:20261201T140000Z
BEGIN:VALARM
TRIGGER:-P1DT2H30M
ACTION:DISPLAY
DESCRIPTION:提前1天2小时30分
END:VALARM
END:VEVENT

END:VCALENDAR
ICS
fi

echo "== 生成 R.java（aapt2）=="
rm -rf testout
mkdir -p testout/gen
"$AAPT2" compile --dir res -o testout/res.zip
"$AAPT2" link -o testout/ignore.apk -I "$AJAR" \
  --manifest AndroidManifest.xml testout/res.zip --java testout/gen \
  --min-sdk-version 26

echo "== 编译（全部 src + ParseTest）=="
find src -name '*.java' > testout/srcs.txt
find testout/gen -name 'R.java' >> testout/srcs.txt
echo test/com/qiuzhao/alarm/ParseTest.java >> testout/srcs.txt
javac -encoding UTF-8 --release 8 -cp "$AJAR" -d testout/classes @testout/srcs.txt

echo "== 运行 =="
# 说明：给 java 加 MSYS_NO_PATHCONV=1 是为了保护类路径里的分隔符，
# 但这同时会关掉 Git Bash 的自动路径转换，所以两段路径都用 cygpath 显式转成
# Windows 风格（类 Unix 上没有 cygpath，原样返回即可）。
to_java_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else echo "$1"; fi
}
MSYS_NO_PATHCONV=1 java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 \
  -cp "$(to_java_path "$PWD/testout/classes")${SEP}$(to_java_path "$AJAR")" \
  com.qiuzhao.alarm.ParseTest
