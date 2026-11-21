#!/usr/bin/env bash
# Kids TV installer for macOS and Linux. Run it with nothing set up:
#
#   bash <(curl -fsSL https://raw.githubusercontent.com/pedroteixeira/kids-tv-launcher/main/install.sh)
#
# It downloads Google's adb (platform-tools) and the app's latest release into
# ~/.kids-tv, connects to the TV over Wireless debugging (pairing it the first time),
# installs the app, sets the PIN, sends your list of titles and switches the watchdog on.
#
# Options:
#   --titles FILE   your titles.json (see README); without it the TV shows the example list
#   --titles-only   only send the titles (and a new PIN, if you type one); don't reinstall
#   --tv IP:PORT    the TV's "IP address & port" from the Wireless debugging screen
#   --apk FILE      install this APK instead of downloading the latest release
#   --build         build the APK from this checkout (needs JDK 17+ and the Android SDK)
set -euo pipefail

REPO=pedroteixeira/kids-tv-launcher
PKG=app.tvdascriancas
WATCHDOG="$PKG/$PKG.Watchdog"
LISTENER="$PKG/$PKG.MediaListener"
FILES="/sdcard/Android/data/$PKG/files"
WORK="${KIDS_TV_HOME:-$HOME/.kids-tv}"

die() { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
# Prompts read the terminal, so they also work under `bash <(curl …)` and `curl … | bash`.
ask() { local reply; read -r -p "$1" reply </dev/tty; printf '%s' "$reply"; }
ask_secret() { local reply; read -r -s -p "$1" reply </dev/tty; echo >/dev/tty; printf '%s' "$reply"; }

usage() {
    cat <<'EOF'
Kids TV installer. Options:
  --titles FILE   your titles.json (see README); without it the TV shows the example list
  --titles-only   only send the titles (and a new PIN, if you type one); don't reinstall
  --tv IP:PORT    the TV's "IP address & port" from the Wireless debugging screen
  --apk FILE      install this APK instead of downloading the latest release
  --build         build the APK from this checkout (needs JDK 17+ and the Android SDK)
EOF
}

titles="" titles_only=0 tv="" apk="" build=0
while [ $# -gt 0 ]; do
    case "$1" in
        --titles) titles="${2:?--titles needs a file}"; shift 2 ;;
        --titles-only) titles_only=1; shift ;;
        --tv) tv="${2:?--tv needs IP:PORT}"; shift 2 ;;
        --apk) apk="${2:?--apk needs a file}"; shift 2 ;;
        --build) build=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option: $1 (see --help)" ;;
    esac
done
[ -z "$titles" ] || [ -f "$titles" ] || die "titles file not found: $titles"
[ -z "$apk" ] || [ -f "$apk" ] || die "APK not found: $apk"
# Absolute paths: --build changes directory.
[ -z "$titles" ] || titles="$(cd "$(dirname "$titles")" && pwd)/$(basename "$titles")"
[ -z "$apk" ] || apk="$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")"
mkdir -p "$WORK"

# --- adb: the one on PATH if it can pair (platform-tools 30+), else Google's, downloaded once.
adb_ok() { "$1" version 2>/dev/null | grep -qE '^Version (3[0-9]|[4-9][0-9])'; }
if command -v adb >/dev/null && adb_ok adb; then
    ADB=adb
else
    ADB="$WORK/platform-tools/adb"
    if ! adb_ok "$ADB"; then
        case "$(uname -s)" in
            Darwin) os=darwin ;;
            Linux) os=linux ;;
            *) die "This script is for macOS and Linux; on Windows use install.ps1." ;;
        esac
        command -v unzip >/dev/null || die "unzip is missing (Debian/Ubuntu: sudo apt install unzip)"
        say "Downloading adb (Google's platform-tools)…"
        curl -fL# -o "$WORK/platform-tools.zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"
        rm -rf "$WORK/platform-tools"
        unzip -q -o "$WORK/platform-tools.zip" -d "$WORK"
        rm -f "$WORK/platform-tools.zip"
    fi
fi

# --- the APK
if [ "$titles_only" = 0 ]; then
    if [ "$build" = 1 ]; then
        cd "$(dirname "$0")"
        [ -x ./gradlew ] || die "--build runs from a checkout of $REPO"
        say "Building…"
        ./gradlew assembleRelease -q
        apk="$PWD/app/build/outputs/apk/release/app-release.apk"
    elif [ -z "$apk" ]; then
        say "Downloading the latest Kids TV release…"
        apk="$WORK/kids-tv.apk"
        curl -fL# -o "$apk" "https://github.com/$REPO/releases/latest/download/kids-tv.apk" \
            || die "Couldn't download the release from github.com/$REPO/releases"
    fi
fi

# --- connect to the TV
cat <<'EOF'

On the TV (once):
  1. Settings → System → About → click "Android TV OS build" 7 times (developer mode).
  2. Settings → System → Developer options → Wireless debugging → On.
     The TV must be on Wi-Fi, on the same network as this computer.
That screen shows the TV's "IP address & port". The port changes when the TV restarts.
EOF
[ -n "$tv" ] || tv="$(ask $'\nIP address & port (e.g. 192.168.1.20:37415): ')"
[[ "$tv" =~ ^[^:]+:[0-9]+$ ]] || die "Expected IP:PORT, e.g. 192.168.1.20:37415"
ip="${tv%:*}"

connected() { [ "$("$ADB" -s "$tv" get-state 2>/dev/null)" = device ]; }
"$ADB" connect "$tv" >/dev/null 2>&1 || true
if ! connected; then
    cat <<'EOF'

This computer isn't paired with the TV yet. On the Wireless debugging screen choose
"Pair device with pairing code": the TV shows a 6-digit code and a second port.
EOF
    pair_port="$(ask 'Pairing port (after the ":" in the pairing popup): ')"
    code="$(ask 'Pairing code: ')"
    "$ADB" pair "$ip:$pair_port" "$code" || die "Pairing failed — check the code and port, then run again."
    "$ADB" disconnect "$tv" >/dev/null 2>&1 || true
    "$ADB" connect "$tv" >/dev/null 2>&1 || true
    for _ in 1 2 3 4 5; do connected && break; sleep 1; done
    connected || die "Paired, but couldn't connect to $tv — check the IP address & port on the TV."
fi
export ANDROID_SERIAL="$tv"
# adb shell reads stdin; keep it off the terminal so it can't swallow typed answers.
tv_shell() { "$ADB" shell "$@" </dev/null; }
echo "connected: $(tv_shell getprop ro.product.model | tr -d '\r')"
tv_shell pm list features | grep -q leanback \
    || echo "warning: this device doesn't look like a Google TV / Android TV; continuing anyway."

# --- PIN: required the first time, optional afterwards
installed=0
tv_shell pm path "$PKG" 2>/dev/null | grep -q package: && installed=1
pin=""
while :; do
    if [ "$installed" = 1 ]; then
        pin="$(ask_secret $'\nNew PIN to leave kids mode (4-8 digits; Enter keeps the current one): ')"
        [ -z "$pin" ] && break
    else
        pin="$(ask_secret $'\nChoose the PIN to leave kids mode (4-8 digits): ')"
    fi
    [[ "$pin" =~ ^[0-9]{4,8}$ ]] || { echo "The PIN must be 4 to 8 digits."; continue; }
    [ "$(ask_secret 'Type it again: ')" = "$pin" ] && break
    echo "They don't match — again."
done

# --- install
if [ "$titles_only" = 0 ]; then
    say "Installing…"
    if ! out="$("$ADB" install -r "$apk" 2>&1)"; then
        if grep -q INSTALL_FAILED_UPDATE_INCOMPATIBLE <<<"$out"; then
            echo "The TV has a copy signed with another key (e.g. built from source)."
            [ "$(ask 'Uninstall it and install this one? Its PIN and history go with it [y/N] ')" = y ] \
                || die "Nothing changed."
            "$ADB" uninstall "$PKG" >/dev/null
            if [ -z "$pin" ]; then
                while :; do
                    pin="$(ask_secret 'Choose the PIN to leave kids mode (4-8 digits): ')"
                    [[ "$pin" =~ ^[0-9]{4,8}$ ]] && [ "$(ask_secret 'Type it again: ')" = "$pin" ] && break
                    echo "4 to 8 digits, typed the same twice."
                done
            fi
            "$ADB" install "$apk" >/dev/null
        else
            die "$out"
        fi
    fi
    echo "installed: $PKG"
elif [ "$installed" = 0 ]; then
    die "Kids TV isn't on this TV yet: run without --titles-only first."
fi

# --- settings the app reads from its files folder (it picks them up on its own)
tv_shell mkdir -p "$FILES"
if [ -n "$pin" ]; then
    # Through stdin, so the PIN never sits in a file on this computer.
    # The app can't read a file only adb's user and group may read: make it readable (no
    # other app can see this folder anyway); the app moves the PIN into private storage.
    printf '%s' "$pin" | "$ADB" shell "cat > $FILES/pin && chmod 666 $FILES/pin"
    echo "PIN set"
fi
if [ -n "$titles" ]; then
    "$ADB" push -q "$titles" "$FILES/titles.json"
    tv_shell chmod 666 "$FILES/titles.json"
    echo "titles sent: $titles"
fi

if [ "$titles_only" = 0 ]; then
    # TCL TVs only let the system start a third-party app's services when the app holds
    # TCL's AUTO_START app-op; without it the watchdog stays enabled but never runs.
    # Other TVs don't have the op — the error is ignored there.
    tv_shell appops set "$PKG" AUTO_START allow 2>/dev/null || true

    # The watchdog is an accessibility service, and Android greys its toggle out for
    # apps from outside the Play Store, so it's switched on here. Ours is removed and
    # added back (after a reinstall the system may keep it "on" without running it);
    # any other enabled services are kept.
    current="$(tv_shell settings get secure enabled_accessibility_services | tr -d '\r')"
    others="$(printf '%s' "$current" | tr ':' '\n' | grep -v -e '^null$' -e '^$' -e "^$WATCHDOG\$" | paste -sd: - || true)"
    if [ -n "$others" ]; then
        tv_shell settings put secure enabled_accessibility_services "$others"
    else
        tv_shell settings delete secure enabled_accessibility_services >/dev/null
    fi
    sleep 1
    tv_shell settings put secure enabled_accessibility_services "${others:+$others:}$WATCHDOG"
    tv_shell settings put secure accessibility_enabled 1
    # Reading other apps' "now playing" needs a notification listener.
    tv_shell cmd notification allow_listener "$LISTENER"
    echo "watchdog on"
fi

tv_shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
say "Done — Kids TV is open on the TV."
cat <<EOF
- Hand the TV over with "Turn on kids mode"; leaving it takes the PIN.
- To change the titles later: edit your titles.json and run
    bash <(curl -fsSL https://raw.githubusercontent.com/$REPO/main/install.sh) --titles-only --titles <file>
- You can turn Wireless debugging off now; the app keeps working.
EOF
