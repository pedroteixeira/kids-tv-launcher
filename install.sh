#!/usr/bin/env bash
# Install the kids' TV app on a Google TV over ADB and switch its watchdog on.
#   ./install.sh <ip:port>     (builds the app, installs it, switches the watchdog on)
# The TV must be paired once (Opções do desenvolvedor → Depuração por Wi-Fi →
# Parear com código: `adb pair <ip:pairport> <code>`). The port changes when the TV
# restarts or debugging is toggled — read it on that same screen.
set -euo pipefail
cd "$(dirname "$0")"

TARGET="${1:?usage: install.sh <ip:port>}"
PKG=app.tvdascriancas
WATCHDOG="$PKG/$PKG.Watchdog"
LISTENER="$PKG/$PKG.MediaListener"
APK=app/build/outputs/apk/debug/app-debug.apk

# Build (JDK 17+ needed; Android Studio's bundled one works: JAVA_HOME=<studio>/jbr).
./gradlew assembleDebug -q

adb connect "$TARGET" >/dev/null
export ANDROID_SERIAL="$TARGET"
adb get-state >/dev/null

adb install -r "$APK" >/dev/null
echo "instalado: $PKG"

# TCL TVs only let the system start a third-party app's services when the app holds
# TCL's AUTO_START app-op ("TclAppBoot: forbid bind service … callee doesn't have
# OP_AUTO_START permission"); without it the watchdog stays enabled but never bound,
# e.g. after a reboot. Other TVs don't have the op — ignore the error there.
adb shell appops set "$PKG" AUTO_START allow 2>/dev/null || true

# Accessibility service: Android greys the toggle out for sideloaded apps, so set it
# directly. Always drop ours and add it back: after a reinstall the system may keep it
# "enabled" without binding it again (seen on the TCL), and rewriting the setting
# forces the bind. Any other enabled services are kept.
current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
others="$(printf '%s' "$current" | tr ':' '\n' | grep -v -e '^null$' -e "^$WATCHDOG\$" | paste -sd: - || true)"
if [ -n "$others" ]; then
    adb shell settings put secure enabled_accessibility_services "$others"
else
    adb shell settings delete secure enabled_accessibility_services >/dev/null
fi
sleep 1
adb shell settings put secure enabled_accessibility_services "${others:+$others:}$WATCHDOG"
adb shell settings put secure accessibility_enabled 1
# "Now playing" of other apps (MediaSessionManager) needs a notification listener.
adb shell cmd notification allow_listener "$LISTENER"
echo "vigia instalado (só age com o modo crianças ligado, no app)"
