#!/usr/bin/env bash
# Read-only: waits for the monitor on the S24 Ultra, logs modes/active rate/votes, posts phone notifications.
ADB="${ADB:-adb}"
DEV="${DEV:?Set DEV to the authorized ADB device endpoint}"
DIR="$(dirname "$0")"
LOG="$DIR/dex_hires_watch.log"
: > "$LOG"
log() { echo "[$(date +%T)] $*" | tee -a "$LOG"; }
sh_() { "$ADB" -s "$DEV" shell "$@"; }
notify() { sh_ cmd notification post -S bigtext -t "'Claude: 240Hz test'" claude_dex "'$1'" >/dev/null 2>&1; }
ext_line() { sh_ dumpsys display | grep "mBaseDisplayInfo=DisplayInfo{" | grep "type EXTERNAL" | head -1; }

notify "Ready. DeX restriction lifted. Plug the monitor into the phone now and wait for the Done message."
log "HighRes flag: $(sh_ dumpsys activity mt | grep -E 'HighResolutions|DisplayPortState' | tr -s ' ' | tr '\n' ' ')"
log "Waiting for a stable external display (up to 20 min)..."
ID=""; STABLE=0; MODES=""
for i in $(seq 1 240); do
  "$ADB" connect "$DEV" >/dev/null 2>&1
  NEW=$(ext_line | grep -oE 'displayId [0-9]+' | head -1 | awk '{print $2}')
  if [ -n "$NEW" ] && [ "$NEW" = "$ID" ]; then STABLE=$((STABLE+1)); else STABLE=0; ID="$NEW"; fi
  [ -n "$NEW" ] && [ "$STABLE" -eq 0 ] && log "Saw external display id=$NEW, checking it stays connected..."
  if [ "$STABLE" -ge 3 ]; then
    MODES=$(sh_ cmd display get-supported-modes "$ID")
    echo "$MODES" | grep -q "Refresh Rate" && break
    STABLE=0
  fi
  sleep 5
done
if ! echo "$MODES" | grep -q "Refresh Rate"; then
  log "No stable external display seen."
  notify "I never saw the monitor stay connected. Nothing changed. Plug the monitor back into your PC."
  exit 1
fi

notify "Monitor detected. Reading its modes now (about 20 seconds)..."
sleep 6
L=$(ext_line)
log "Display: $(echo "$L" | grep -oE 'DisplayInfo\{"[^"]*"') $(echo "$L" | grep -oE 'real [0-9]+ x [0-9]+')"
log "Active renderFrameRate: $(echo "$L" | grep -oE 'renderFrameRate [0-9.]+')"
log "Supported modes:"; echo "$MODES" | tee -a "$LOG"
log "DisplayPort state after connect: $(sh_ dumpsys activity mt | grep -E 'HighResolutions|DisplayPortState' | tr -s ' ' | tr '\n' ' ')"
sh_ dumpsys display > "$DIR/dex_hires_display.txt" 2>&1
sh_ dumpsys SurfaceFlinger > "$DIR/dex_hires_sf.txt" 2>&1
sh_ dumpsys android.hardware.graphics.composer3.IComposer/default > "$DIR/dex_hires_hwc.txt" 2>&1
sh_ logcat -d -v time | grep -E "SDM|HWCSession|DisplayDeviceRepository|External display" | tail -80 > "$DIR/dex_hires_logcat.txt"

MAX=$(echo "$MODES" | grep -oE 'Refresh Rate: +[0-9.]+' | awk '{print $3}' | sort -g | tail -1)
CUR=$(echo "$L" | grep -oE 'renderFrameRate [0-9.]+' | awk '{print $2}')
log "Fastest mode offered: ${MAX}Hz, running at: ${CUR}Hz"
if awk "BEGIN{exit !(${CUR:-0} > 61)}"; then
  notify "Done! Monitor is running at ${CUR%.*}Hz. Plug the monitor back into your PC and tell Claude."
elif awk "BEGIN{exit !(${MAX:-0} > 76)}"; then
  notify "Done. Progress: the phone now sees modes up to ${MAX%.*}Hz, but started at ${CUR%.*}Hz. Plug the monitor back into your PC and tell Claude."
else
  notify "Done. Still only 60Hz modes offered (max ${MAX%.*}Hz). Plug the monitor back into your PC and tell Claude."
fi
log "Finished."
