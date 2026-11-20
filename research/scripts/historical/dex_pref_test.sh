#!/usr/bin/env bash
# Saves the fastest 1080p mode as the monitor's preferred mode, then asks for a replug
# and checks whether the monitor comes back in that mode.
ADB="${ADB:-adb}"
DEV="${DEV:?Set DEV to the authorized ADB device endpoint}"
DIR="$(dirname "$0")"
LOG="$DIR/dex_pref_test.log"
: > "$LOG"
log() { echo "[$(date +%T)] $*" | tee -a "$LOG" >&2; }
sh_() { "$ADB" -s "$DEV" shell "$@"; }
N=0
notify() { log "NOTIFY: $1"; sh_ cmd notification post -S bigtext -t "'Claude $(date +%H:%M:%S) '" "claude_$(date +%s%N)" "'$1'" >/dev/null 2>&1; }
ext_id() { sh_ dumpsys display | grep "mBaseDisplayInfo=DisplayInfo{" | grep "type EXTERNAL" | grep -oE 'displayId [0-9]+' | head -1 | awk '{print $2}'; }
ext_rate() { sh_ dumpsys display | grep "mBaseDisplayInfo=DisplayInfo{" | grep "type EXTERNAL" | grep -oE 'renderFrameRate [0-9.]+' | head -1 | awk '{print $2}'; }
drop_reason() {
  local r; r=$(sh_ logcat -d -v time -t 400 | grep -E "status = disconnected|DP_ERR|dp_|link train|Display device removed|usbpd|PD_|cc_state|typec" | tail -4 | cut -c1-160)
  log "Drop context: $r"
}
wait_stable() {  # prints display id once it has stayed connected ~15s; narrates connects/drops
  local id="" st=0 new
  for i in $(seq 1 240); do
    "$ADB" connect "$DEV" >/dev/null 2>&1
    new=$(ext_id)
    if [ -n "$new" ] && [ "$new" != "$id" ]; then notify "Monitor connected (display $new) at $(ext_rate)Hz. Checking it stays connected for 15s..." >&2; fi
    if [ -z "$new" ] && [ -n "$id" ]; then notify "Monitor DROPPED after ~$((st*5))s. Reason in log below. Waiting for it to come back..." >&2; drop_reason >&2; fi
    if [ -n "$new" ] && [ "$new" = "$id" ]; then st=$((st+1)); else st=0; id="$new"; fi
    [ "$st" -ge 3 ] && { echo "$id"; return 0; }
    sleep 5
  done
  return 1
}

[ "$(sh_ dumpsys activity mt | grep -c 'HighResolutionsForExternalDesktopEnabled=true')" -ge 1 ] || {
  sh_ "CLASSPATH=/data/local/tmp/hires.dex app_process / HiRes on" >/dev/null 2>&1; log "Re-enabled HiRes"; }
notify "Step 1: Plug the monitor into the phone (or leave it plugged in). Wait for the next message."
log "Waiting for monitor..."
ID=$(wait_stable) || { log "No monitor"; notify "Never saw the monitor. Nothing changed."; exit 1; }
MODES=$(sh_ cmd display get-supported-modes "$ID"); echo "$MODES" >> "$LOG"
BEST=$(echo "$MODES" | grep "1920x1080," | grep -oE 'Refresh Rate: +[0-9.]+' | awk '{print $3}' | sort -g | tail -1)
log "Display $ID connected at $(ext_rate)Hz; fastest 1080p mode: ${BEST}Hz"
if ! awk "BEGIN{exit !(${BEST:-0} > 61)}"; then
  notify "Done. No 1080p mode above 60Hz offered this time. Plug the monitor back into your PC."; log "No >60 mode"; exit 0
fi
log "set-user-preferred-display-mode 1920 1080 ${BEST%.*} $ID"
sh_ cmd display set-user-preferred-display-mode 1920 1080 "${BEST%.*}" "$ID" 2>&1 | tee -a "$LOG"
log "Stored preference: $(sh_ cmd display get-user-preferred-display-mode "$ID" 2>&1)"
sleep 3; log "Rate right after setting (no replug): $(ext_rate)Hz"

notify "Step 2: Saved ${BEST%.*}Hz as preferred. Now UNPLUG the monitor from the phone, wait 5 seconds, and plug it back in."
log "Waiting for unplug..."
for i in $(seq 1 120); do [ -z "$(ext_id)" ] && break; sleep 2; done
log "Unplugged. Waiting for replug..."
ID2=$(wait_stable) || { log "No replug"; notify "Never saw the monitor come back."; exit 1; }
R=$(ext_rate); log "After replug: display $ID2 at ${R}Hz"
sh_ dumpsys display > "$DIR/dex_pref_display.txt" 2>&1
sh_ dumpsys SurfaceFlinger > "$DIR/dex_pref_sf.txt" 2>&1
sh_ logcat -d -v time | grep -E "SDM|HWCSession|DisplayDeviceRepository|External display|setDesiredMode|UserPreferred|preferred" | tail -120 > "$DIR/dex_pref_logcat.txt"
if awk "BEGIN{exit !(${R:-0} > 61)}"; then
  notify "SUCCESS! Monitor came back at ${R%.*}Hz. Keep using it, or plug it back into your PC and tell Claude."
else
  notify "Done. Monitor came back at ${R%.*}Hz, so the saved preference did not apply at connect. Plug the monitor back into your PC and tell Claude."
fi
log "Finished."
