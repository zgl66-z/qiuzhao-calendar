#!/usr/bin/env bash
# 手工打包 Android APK（绕开 Gradle / AGP）：aapt2 link → javac → d8 → 塞 dex → zipalign → apksigner
# 用法： bash build.sh
set -euo pipefail
cd "$(dirname "$0")"

# 本机覆盖（local.env 已在 .gitignore 中，不会进仓库）
if [ -f local.env ]; then . ./local.env; fi

# 依次尝试：环境变量 → 各平台常见安装位置
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
KS=keystore.p12
KSPASS=android

# 工具路径：优先无后缀（类 Unix），其次 .exe / .bat（Windows）
pick() {
  for c in "$1/$2" "$1/$2.exe" "$1/$2.bat"; do
    if [ -f "$c" ]; then echo "$c"; return 0; fi
  done
  echo "找不到 $2（在 $1 下）" >&2
  return 1
}
AAPT2=$(pick "$BT" aapt2)
D8=$(pick "$BT" d8)
ZIPALIGN=$(pick "$BT" zipalign)
APKSIGNER=$(pick "$BT" apksigner)

echo "== 0. 清理 =="
# 先删掉旧产物：万一编译失败，目录里不会留一个旧 APK 冒充"构建成功"
rm -f 别错过.apk 别错过.apk.idsig
rm -rf build
mkdir -p build/classes build/dex

echo "== 1. aapt2 compile（编译 res：图标资源）=="
"$AAPT2" compile --dir res -o build/res.zip

echo "== 2. aapt2 link（打包 manifest + 资源，并生成 R.java）=="
"$AAPT2" link \
  -o build/base.apk \
  -I "$AJAR" \
  --manifest AndroidManifest.xml \
  build/res.zip \
  --java build/gen \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 1 --version-name 1.0

echo "== 3. javac（--release 8，只用框架 API）=="
find src -name '*.java' > build/sources.txt
find build/gen -name 'R.java' >> build/sources.txt
javac -encoding UTF-8 --release 8 -cp "$AJAR" -d build/classes @build/sources.txt

echo "== 4. d8 → dex =="
find build/classes -name '*.class' > build/classlist.txt
"$D8" --lib "$AJAR" --min-api 26 --output build/dex @build/classlist.txt

echo "== 5. 把 classes.dex 塞进 APK =="
python - <<'PY'
import zipfile, os, shutil
src = 'build/base.apk'
dst = 'build/unaligned.apk'
shutil.copyfile(src, dst)
dexs = sorted(f for f in os.listdir('build/dex') if f.endswith('.dex'))
with zipfile.ZipFile(dst, 'a', zipfile.ZIP_DEFLATED) as z:
    for d in dexs:
        z.write(os.path.join('build/dex', d), d)
        print('   +', d)
PY

echo "== 6. zipalign =="
"$ZIPALIGN" -f -p 4 build/unaligned.apk build/aligned.apk

echo "== 7. 生成签名密钥（首次）=="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storetype PKCS12 -alias k \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=QiuzhaoAlarm, O=Personal, C=CN"
fi

echo "== 8. apksigner 签名 =="
"$APKSIGNER" sign \
  --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" --ks-key-alias k \
  --min-sdk-version 26 \
  --out 别错过.apk \
  build/aligned.apk

echo "== 9. 验证签名 =="
"$APKSIGNER" verify --verbose --print-certs 别错过.apk | head -20

echo
echo "== 完成 =="
ls -la 别错过.apk
