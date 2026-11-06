#!/bin/bash
# 离线编译 APK 的完整流程：aapt2 -> javac -> d8 -> zip -> zipalign -> apksigner
#
# 依赖：JDK 11+、Android SDK（build-tools + platform 33 以上）、python3
# 用法：./build.sh
#
# 可用环境变量覆盖：
#   ANDROID_HOME     Android SDK 路径，默认自动探测
#   KS_PASS          签名密钥密码，默认 changeit
#   BUILD_TOOLS      指定 build-tools 版本，默认取已安装的最高版本
set -e

cd "$(dirname "$0")"

# ---------- 定位 SDK ----------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" \
           "$HOME/Android/Sdk" /usr/lib/android-sdk; do
    [ -d "$c" ] && SDK="$c" && break
  done
fi
[ -z "$SDK" ] && { echo "错误：找不到 Android SDK，请设置 ANDROID_HOME"; exit 1; }

# 取最高版本 build-tools
if [ -z "$BUILD_TOOLS" ]; then
  BUILD_TOOLS=$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)
fi
[ -z "$BUILD_TOOLS" ] && { echo "错误：$SDK/build-tools 为空，请安装 build-tools"; exit 1; }
BT="$SDK/build-tools/$BUILD_TOOLS"

# 取最高版本 platform 的 android.jar
PLATFORM=$(ls "$SDK/platforms" 2>/dev/null | grep '^android-' | sort -V | tail -1)
[ -z "$PLATFORM" ] && { echo "错误：未安装 platform，请安装 android-33 或更高"; exit 1; }
AJAR="$SDK/platforms/$PLATFORM/android.jar"

echo "SDK      : $SDK"
echo "build-tools: $BUILD_TOOLS"
echo "platform : $PLATFORM"

# Windows 下 javac 需要反斜杠路径
AJAR_W=$(cygpath -w "$AJAR" 2>/dev/null || echo "$AJAR")

# ---------- 1. 编译资源 ----------
echo "[1/6] 编译资源"
rm -rf build
mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip

# ---------- 2. 链接清单 ----------
echo "[2/6] 链接清单与资源"
"$BT/aapt2" link \
  -o build/app-unsigned.apk \
  -I "$AJAR_W" \
  --manifest AndroidManifest.xml \
  -R build/res.zip \
  --java build/gen \
  --min-sdk-version 24 \
  --target-sdk-version 33 \
  --auto-add-overlay

# ---------- 3. 编译 Java ----------
echo "[3/6] 编译 Java"
javac -source 8 -target 8 -encoding UTF-8 -nowarn \
  -bootclasspath "$AJAR_W" -classpath "$AJAR_W" \
  -d build/classes \
  $(find java build/gen -name "*.java")

# ---------- 4. dex ----------
echo "[4/6] 转 dex"
"$BT/d8" --lib "$AJAR_W" --min-api 24 --output build/dex \
  $(find build/classes -name "*.class")

# ---------- 5. 打包 dex ----------
echo "[5/6] 打包 classes.dex"
if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi
"$PY" - <<'PY'
import shutil, zipfile
shutil.copy('build/app-unsigned.apk', 'build/app-with-dex.apk')
with zipfile.ZipFile('build/app-with-dex.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
print('  classes.dex 已加入')
PY

# ---------- 6. 对齐 + 签名 ----------
echo "[6/6] 对齐并签名"
"$BT/zipalign" -p -f 4 build/app-with-dex.apk build/app-aligned.apk

KS_PASS="${KS_PASS:-changeit}"
if [ ! -f keystore.jks ]; then
  echo "  未找到 keystore.jks，自动生成一个（密码 $KS_PASS）"
  keytool -genkeypair \
    -keystore keystore.jks -alias timersign \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=NarutoTimer, OU=Personal, O=Personal, L=Beijing, ST=Beijing, C=CN" \
    >/dev/null 2>&1
fi

"$BT/apksigner" sign \
  --ks keystore.jks --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 24 \
  --out timer.apk build/app-aligned.apk

echo
echo "编译完成 -> $(pwd)/timer.apk"
ls -lh timer.apk
