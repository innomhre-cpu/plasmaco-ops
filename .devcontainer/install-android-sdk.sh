#!/usr/bin/env bash
# Installs the Android command-line tools and the SDK packages this project builds against (compileSdk 35).
set -euo pipefail
SDK="${ANDROID_HOME:-/opt/android-sdk}"
TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  sudo mkdir -p "$SDK" && sudo chown "$(id -u):$(id -g)" "$SDK"
  curl -fsSL "https://dl.google.com/android/repository/$TOOLS_ZIP" -o "/tmp/$TOOLS_ZIP"
  unzip -q "/tmp/$TOOLS_ZIP" -d "$SDK/cmdline-tools-tmp"
  mkdir -p "$SDK/cmdline-tools" && mv "$SDK/cmdline-tools-tmp/cmdline-tools" "$SDK/cmdline-tools/latest" && rm -rf "$SDK/cmdline-tools-tmp" "/tmp/$TOOLS_ZIP"
fi
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$SDK" > local.properties
echo "Android SDK ready in $SDK"
