#!/usr/bin/env bash
# Waits for an external monitor on the phone, then tries to push it above 60Hz.
# Posts phone notifications (visible in DeX) at start and finish. Log: dex_hz_test.log
ADB="${ADB:-adb}"
DEV="${DEV:?Set DEV to the authorized ADB device endpoint}"
LOG="$(dirname "$0")/dex_hz_test.log"
: > "$LOG"
log() { echo "[$(date +%T)] $*" | tee -a "$LOG"; }
sh_() { "$ADB" -s "$DEV" shell "$@"; }
notify() { sh_ cmd notification post -S bigtext -t "'Claude DeX test'" claude_dex "'$1'" >/dev/null 2>&1; }

ext_line() { sh_ dumpsys display | grep "mBaseDisplayInfo=DisplayInfo{" | grep "type EXTERNAL" | head -1; }
ext_rate() { ext_line | grep -oE 'renderFrameRate [0-9.]+' | awk '{print $2}'; }

log "Waiting for a stable external display (up to 20 min)..."
ID=""; STABLE=0; MODES=""
for i in $(seq 1 240); do
  "$ADB" connect "$DEV" >/dev/null 2>&1
  L=$(ext_line)
  NEW=$(echo "$L" | grep -oE 'displayId [0-9]+' | head -1 | awk '{print $2}')
  if [ -n "$NEW" ] && [ "$NEW" = "$ID" ]; then STABLE=$((STABLE+1)); else STABLE=0; ID="$NEW"; fi
  [ -n "$NEW" ] && [ "$STABLE" -eq 0 ] && log "Saw external display id=$NEW, checking it stays connected..."
  if [ "$STABLE" -ge 3 ]; then
    MODES=$(sh_ cmd display get-supported-modes "$ID")
    if echo "$MODES" | grep -q "Refresh Rate"; then break; fi
    log "Display $ID has no readable modes yet, still waiting"; STABLE=0
  fi
  sleep 5
done
if [ -z "$MODES" ] || ! echo "$MODES" | grep -q "Refresh Rate"; then
  log "No stable external display seen. Giving up."
  notify "I never saw the monitor stay connected, so nothing was changed. Plug the monitor back into your PC."
  exit 1
fi

notify "Monitor detected. Testing refresh rates now, please wait about 1 minute..."
L=$(ext_line)
log "External display id=$ID"
log "Name/res: $(echo "$L" | grep -oE 'DisplayInfo\{"[^"]*"') $(echo "$L" | grep -oE 'real [0-9]+ x [0-9]+')"
log "Current renderFrameRate: $(ext_rate)"
sh_ dumpsys display > "$(dirname "$0")/dex_dumpsys_before.txt" 2>&1
log "Supported modes:"
echo "$MODES" | tee -a "$LOG"

W=$(echo "$L" | grep -oE 'real [0-9]+ x [0-9]+' | awk '{print $2}')
H=$(echo "$L" | grep -oE 'real [0-9]+ x [0-9]+' | awk '{print $4}')
BEST=$(echo "$MODES" | grep -E "Resolution: ${W}x${H}," | grep -oE 'Refresh Rate: +[0-9.]+' | awk '{print $3}' | sort -g | tail -1)
TOP=$(echo "$MODES" | grep -oE 'Resolution: [0-9]+x[0-9]+, Refresh Rate: +[0-9.]+' | sort -t: -k3 -g | tail -1)
log "Current res ${W}x${H}; best rate at this res: ${BEST:-none}; fastest mode overall: ${TOP:-none}"

if [ -z "$BEST" ] || awk "BEGIN{exit !($BEST <= 61)}"; then
  if [ -n "$TOP" ] && awk "BEGIN{exit !($(echo "$TOP" | awk '{print $NF}') > 61)}"; then
    W=$(echo "$TOP" | grep -oE '[0-9]+x[0-9]+' | cut -dx -f1); H=$(echo "$TOP" | grep -oE '[0-9]+x[0-9]+' | cut -dx -f2)
    BEST=$(echo "$TOP" | awk '{print $NF}')
    log "No >60Hz at current res; will try ${W}x${H}@${BEST} instead."
  else
    log "RESULT: monitor/cable only reports <=60Hz modes to the phone. Nothing to unlock."
    notify "Done. Your monitor only offers 60Hz to the phone, so it can't go higher. You can plug the monitor back into your PC."
    exit 0
  fi
fi

RATE=${BEST%.*}
log "Attempt A: set-user-preferred-display-mode $W $H $RATE $ID"
sh_ cmd display set-user-preferred-display-mode "$W" "$H" "$RATE" "$ID" 2>&1 | tee -a "$LOG"
sleep 8; R=$(ext_rate); log "renderFrameRate after A: $R"

if awk "BEGIN{exit !(${R:-0} <= 61)}"; then
  log "Attempt B: global set-user-preferred-display-mode $W $H $RATE"
  sh_ cmd display set-user-preferred-display-mode "$W" "$H" "$RATE" 2>&1 | tee -a "$LOG"
  sleep 8; R=$(ext_rate); log "renderFrameRate after B: $R"
fi
log "Preferred mode now: $(sh_ cmd display get-user-preferred-display-mode "$ID" 2>&1)"
sh_ dumpsys display > "$(dirname "$0")/dex_dumpsys_after.txt" 2>&1

if awk "BEGIN{exit !(${R:-0} > 61)}"; then
  log "RESULT: SUCCESS - external display running at ${R}Hz"
  notify "Done! Monitor is now running at ${R%.*}Hz. Check if it feels smoother, then plug the monitor back into your PC."
else
  log "RESULT: mode request did not stick (still ${R}Hz)"
  notify "Done. The phone refused to go above 60Hz (still ${R%.*}Hz). Plug the monitor back into your PC and I will try other tricks."
fi
