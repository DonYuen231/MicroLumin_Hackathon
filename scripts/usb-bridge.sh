#!/bin/sh
# Links the M6 glasses and the Samsung phone through this laptop over USB.
# Run from Git Bash and leave it open: sh scripts/usb-bridge.sh
# Add --once to set up the link and exit.

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1

serial() {
  "$ADB" devices -l | tr -d '\r' | grep -E "[[:space:]]device .*model:$1" | awk '{print $1}' | head -1
}

bridged() {
  "$ADB" -s "$G" reverse --list 2>/dev/null | grep -q 'tcp:8080' &&
    "$ADB" -s "$P" reverse --list 2>/dev/null | grep -q 'tcp:8081' &&
    "$ADB" forward --list 2>/dev/null | grep "$P" | grep -q 'tcp:18080' &&
    "$ADB" forward --list 2>/dev/null | grep "$G" | grep -q 'tcp:18081'
}

link() {
  "$ADB" -s "$P" forward tcp:18080 tcp:8080 >/dev/null 2>&1
  "$ADB" -s "$G" reverse tcp:8080 tcp:18080 >/dev/null 2>&1
  "$ADB" -s "$G" forward tcp:18081 tcp:8081 >/dev/null 2>&1
  "$ADB" -s "$P" reverse tcp:8081 tcp:18081 >/dev/null 2>&1
}

# The phone reads this setting when the app starts, so it restarts only if it needs the change.
point_phone_at_usb() {
  if "$ADB" -s "$P" exec-out run-as app.recall.phone cat files/datastore/settings.preferences_pb 2>/dev/null |
    tr -d '\000' | grep -a -q '127\.0\.0\.1'; then
    return
  fi
  printf '{"glasses_address":"127.0.0.1"}' |
    "$ADB" -s "$P" shell "run-as app.recall.phone sh -c 'cat > files/provision.json'"
  "$ADB" -s "$P" shell am force-stop app.recall.phone
  sleep 1
  "$ADB" -s "$P" shell am start -n app.recall.phone/.MainActivity >/dev/null 2>&1
  echo "$(date +%H:%M:%S) phone pointed at the USB link"
}

last_pid=""
phone_ready=""
while :; do
  G=$(serial "${GLASSES_MODEL:?Set GLASSES_MODEL to the model shown by adb devices -l}")
  P=$(serial SM_)
  if [ -n "$G" ] && [ -n "$P" ]; then
    if [ -z "$phone_ready" ]; then
      point_phone_at_usb
      phone_ready=1
    fi
    if ! bridged; then
      link
      last_pid=""
      echo "$(date +%H:%M:%S) USB link ready"
    fi
    pid=$("$ADB" -s "$G" shell pidof app.recall.glasses 2>/dev/null | tr -d '\r')
    if [ -z "$pid" ] || [ "$pid" != "$last_pid" ]; then
      "$ADB" -s "$G" shell am start -n app.recall.glasses/.MainActivity \
        --es phone_host 127.0.0.1 >/dev/null 2>&1
      sleep 1
      last_pid=$("$ADB" -s "$G" shell pidof app.recall.glasses 2>/dev/null | tr -d '\r')
      echo "$(date +%H:%M:%S) glasses using the USB link"
    fi
    [ "$1" = "--once" ] && exit 0
  elif [ "$1" = "--once" ]; then
    echo "Plug in both the glasses and the phone, with USB debugging on."
    exit 1
  fi
  sleep 2
done
