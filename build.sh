#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export ANDROID_HOME="$HOME/Library/Android/sdk"
BT="$ANDROID_HOME/build-tools/34.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-34/android.jar"

rm -rf gen classes out
mkdir -p gen classes out

echo "== aapt2 compile =="
"$BT/aapt2" compile --dir res -o out/compiled_res.zip

echo "== aapt2 link =="
"$BT/aapt2" link -o out/base.apk -I "$PLATFORM" --manifest AndroidManifest.xml \
  -R out/compiled_res.zip -A assets --auto-add-overlay --java gen \
  --min-sdk-version 26 --target-sdk-version 34

echo "== javac =="
# -parameters: without it, d8 8.2.2 crashes with a NullPointerException while processing
# synthetic accessor methods for nested classes (see PRINTHOST_PLAN.md build notes).
"$JAVA_HOME/bin/javac" -source 8 -target 8 -parameters -encoding UTF-8 \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d classes $(find gen src -name "*.java")

echo "== d8 =="
"$BT/d8" --release --output out --lib "$PLATFORM" --min-api 26 $(find classes -name "*.class")

echo "== package/align/sign =="
cd out
cp base.apk withdex.apk
zip -j withdex.apk classes.dex
"$BT/zipalign" -f 4 withdex.apk aligned.apk

if [ ! -f ../debug.keystore ]; then
  keytool -genkeypair -v -keystore ../debug.keystore -storepass android -alias androiddebugkey \
    -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Debug,O=Debug,C=US"
fi

"$BT/apksigner" sign --ks ../debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out signed.apk aligned.apk

echo "== done: out/signed.apk =="
