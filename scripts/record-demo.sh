#!/bin/sh
# Records the wearer's view: the glasses camera with the Recall screen on top.
# Set GLASSES_MODEL to the model shown by adb devices -l.
# Run from Git Bash with the glasses on USB:
#   sh scripts/record-demo.sh start
#   sh scripts/record-demo.sh stop
# Videos are saved to Videos/Recall Demo and are never added to the repo.

ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
export MSYS_NO_PATHCONV=1
: "${GLASSES_MODEL:?Set GLASSES_MODEL to the model shown by adb devices -l}"
ROOT="$(cygpath -m "$USERPROFILE")/Videos/Recall Demo"
G=$("$ADB" devices -l | tr -d '\r' | grep -E "[[:space:]]device .*model:${GLASSES_MODEL:?Set GLASSES_MODEL to the model shown by adb devices -l}" | awk '{print $1}' | head -1)
[ -n "$G" ] || { echo "Plug the glasses into the laptop first."; exit 1; }

case "$1" in
start)
  "$ADB" -s "$G" shell am start -n app.recall.glasses/.MainActivity \
    --ez demo_view true --es phone_host 127.0.0.1 >/dev/null
  sleep 2
  "$ADB" -s "$G" shell "mkdir -p /sdcard/recall-demo && cat > /sdcard/recall-demo/rec.sh" <<'EOF'
i=100
while [ ! -f /sdcard/recall-demo/stop ]; do
  screenrecord --bit-rate 6000000 --time-limit 180 /sdcard/recall-demo/part_$i.mp4
  i=$((i+1))
done
EOF
  "$ADB" -s "$G" shell "nohup sh /sdcard/recall-demo/rec.sh </dev/null >/dev/null 2>&1 &"
  echo "Recording the wearer's view."
  ;;
stop)
  "$ADB" -s "$G" shell "touch /sdcard/recall-demo/stop; pkill -INT screenrecord"
  sleep 3
  DIR="$ROOT/$(date +%Y%m%d_%H%M%S)"
  mkdir -p "$DIR"
  for part in $("$ADB" -s "$G" shell ls /sdcard/recall-demo | tr -d '\r' | grep '^part_'); do
    "$ADB" -s "$G" pull "/sdcard/recall-demo/$part" "$DIR/$part" >/dev/null
  done
  count=$(ls "$DIR" | grep -c '^part_')
  if [ "$count" -eq 1 ]; then
    mv "$DIR"/part_*.mp4 "$DIR/recall-demo.mp4"
  elif [ "$count" -gt 1 ]; then
    ls "$DIR" | grep '^part_' | sed "s/.*/file '&'/" > "$DIR/list.txt"
    docker run --rm -v "$DIR:/v" -w /v --entrypoint ffmpeg backend-backend \
      -hide_banner -loglevel error -f concat -safe 0 -i list.txt -c copy recall-demo.mp4
  fi
  if [ -s "$DIR/recall-demo.mp4" ]; then
    "$ADB" -s "$G" shell rm -rf /sdcard/recall-demo
    rm -f "$DIR"/part_*.mp4 "$DIR/list.txt"
    echo "Saved: $DIR/recall-demo.mp4"
  else
    echo "Could not save the video. The parts are still on the glasses in /sdcard/recall-demo."
  fi
  "$ADB" -s "$G" shell am start -n app.recall.glasses/.MainActivity \
    --ez demo_view false --es phone_host 127.0.0.1 >/dev/null
  ;;
*)
  echo "Usage: sh scripts/record-demo.sh start|stop"
  exit 1
  ;;
esac
