#!/usr/bin/env bash
# Installs pre-built WatchNC APKs without deleting existing application data.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: bash install.sh [--phone SERIAL] [--watch SERIAL] [--apk-dir DIRECTORY]

Connect and authorize your phone and watch with adb first. If a single phone
and watch are connected, they are selected automatically. If multiple devices
of either kind are connected, choose a serial or pass it as an option.

In a release download, APKs are read from the included apks folder. In a source
checkout, they are read from the Gradle debug build directories. --apk-dir uses
phone.apk, wear.apk and face.apk (or their -debug.apk equivalents) from that
folder instead. Set ADB to the adb executable if it cannot be found.
EOF
}

root=$(cd "$(dirname "$0")" && pwd)
adb=${ADB:-adb}
phone=""; watch=""; apk_dir=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --phone|--watch|--apk-dir)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then echo "Missing value for $1" >&2; exit 2; fi
      case "$1" in --phone) phone=$2 ;; --watch) watch=$2 ;; --apk-dir) apk_dir=$2 ;; esac
      shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if ! command -v "$adb" >/dev/null 2>&1 && [ -z "${ADB:-}" ]; then
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "${LOCALAPPDATA:-}/Android/Sdk"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then adb="$sdk/platform-tools/adb"; break; fi
  done
fi
if ! command -v "$adb" >/dev/null 2>&1; then
  echo "adb not found. Download Android SDK Platform-Tools: https://developer.android.com/tools/releases/platform-tools" >&2
  echo "Add its platform-tools folder to PATH, or set ADB to the full path to adb." >&2
  exit 1
fi
if [ -z "$apk_dir" ] && [ -d "$root/apks" ]; then apk_dir="$root/apks"; fi
if [ -n "$apk_dir" ]; then
  if [ -f "$apk_dir/phone.apk" ] || [ -f "$apk_dir/wear.apk" ] || [ -f "$apk_dir/face.apk" ]; then
    phone_apk="$apk_dir/phone.apk"; watch_apk="$apk_dir/wear.apk"; face_apk="$apk_dir/face.apk"
  else
    phone_apk="$apk_dir/phone-debug.apk"; watch_apk="$apk_dir/wear-debug.apk"; face_apk="$apk_dir/face-debug.apk"
  fi
else
  phone_apk="$root/phone/build/outputs/apk/debug/phone-debug.apk"
  watch_apk="$root/wear/build/outputs/apk/debug/wear-debug.apk"
  face_apk="$root/face/build/outputs/apk/debug/face-debug.apk"
fi
for file in "$phone_apk" "$watch_apk" "$face_apk"; do
  if [ ! -f "$file" ]; then
    echo "APK not found: $file" >&2
    echo "Download the complete release bundle or build the apps first; see the README. No apps were installed." >&2
    exit 1
  fi
done

find_devices() {
  local devices serial state rest features
  devices=$("$adb" devices) || { echo "Could not contact adb." >&2; return 1; }
  phones=(); watches=()
  while IFS=$'\t' read -r serial state rest; do
    case "$state" in
      unauthorized) echo "Device $serial is unauthorized. Unlock it and accept the USB or wireless debugging prompt." >&2 ;;
      offline) echo "Device $serial is offline. Reconnect it and check debugging is enabled." >&2 ;;
    esac
    [ "$state" = "device" ] || continue
    [ -n "$serial" ] || continue
    if ! features=$("$adb" -s "$serial" shell pm list features 2>/dev/null); then
      echo "Could not identify $serial; check its debugging authorization." >&2
      continue
    fi
    if printf '%s\n' "$features" | tr -d '\r' | grep -Fxq 'feature:android.hardware.type.watch'; then
      watches+=("$serial")
    else
      phones+=("$serial")
    fi
  done <<< "$devices"
}
echo "Checking connected devices..."
find_devices
if [ -z "${watches[0]+set}" ] && [ -z "$watch" ] && [ -t 0 ]; then
  echo "No watch is connected. You can pair it with wireless debugging now."
  printf 'Pair a watch over Wi-Fi? [y/N] '
  read -r answer
  case "$answer" in
    y|Y|yes|YES)
      echo "On the watch, open Developer options → Wireless debugging → Pair new device."
      printf 'Enter its PAIRING IP:port: '
      read -r pair_address
      [ -n "$pair_address" ] || { echo "No pairing address entered." >&2; exit 1; }
      echo "Enter the pairing code displayed on the watch when adb asks."
      "$adb" pair "$pair_address" || { echo "Pairing failed; check the address and code on the watch." >&2; exit 1; }
      echo "Return to Wireless debugging on the watch. Its connection port is usually different from the pairing port."
      printf 'Enter its CONNECTION IP:port: '
      read -r connect_address
      [ -n "$connect_address" ] || { echo "No connection address entered." >&2; exit 1; }
      "$adb" connect "$connect_address" || { echo "Connection failed; check the watch address." >&2; exit 1; }
      find_devices ;;
  esac
fi

choose() {
  local kind=$1 requested=$2 item found=no
  shift 2
  if [ "$#" -eq 0 ]; then
    echo "No authorized $kind found. Check adb devices and accept the debugging prompt." >&2
    return 1
  fi
  if [ -n "$requested" ]; then
    for item in "$@"; do [ "$item" != "$requested" ] || found=yes; done
    if [ "$found" != yes ]; then echo "Requested $kind '$requested' is not an authorized connected $kind." >&2; return 1; fi
    printf '%s\n' "$requested"; return
  fi
  if [ "$#" -eq 1 ]; then printf '%s\n' "$1"; return; fi
  echo "Connected ${kind}s:" >&2
  for item in "$@"; do echo "  $item" >&2; done
  if [ ! -t 0 ]; then echo "Specify --$kind SERIAL for a non-interactive install." >&2; return 1; fi
  printf 'Enter the %s serial: ' "$kind" >&2
  read -r requested
  for item in "$@"; do [ "$item" != "$requested" ] || found=yes; done
  if [ "$found" != yes ]; then echo "Invalid $kind serial." >&2; return 1; fi
  printf '%s\n' "$requested"
}
phone=$(choose phone "$phone" ${phones[@]+"${phones[@]}"}) || exit 1
watch=$(choose watch "$watch" ${watches[@]+"${watches[@]}"}) || exit 1
phone_api=$("$adb" -s "$phone" shell getprop ro.build.version.sdk | tr -d '\r')
watch_api=$("$adb" -s "$watch" shell getprop ro.build.version.sdk | tr -d '\r')
case "$phone_api:$watch_api" in *[!0-9:]*|:*|*:) echo "Could not determine device Android versions." >&2; exit 1 ;; esac
if [ "$phone_api" -lt 26 ] || [ "$watch_api" -lt 33 ]; then
  echo "Requires Android 8+ on the phone and Wear OS 4+ (API 33+) for the watch face." >&2
  exit 1
fi
echo "Phone: $phone (Android API $phone_api)"
echo "Watch: $watch (Android API $watch_api)"
echo "Installing the phone app, watch app, and watch face..."
install_apk() {
  local serial=$1 file=$2
  if ! "$adb" -s "$serial" install -r "$file"; then
    echo "Install failed for $file on $serial. Earlier installs may already have succeeded." >&2
    echo "If an existing app has a different signing key or a newer version, Android may reject the update." >&2
    echo "No apps or data were uninstalled. See the README for update and signing guidance." >&2
    exit 1
  fi
}
install_apk "$phone" "$phone_apk"
install_apk "$watch" "$watch_apk"
install_apk "$watch" "$face_apk"
echo "Installed. Open WatchNC on both devices and follow the setup steps in the README."
