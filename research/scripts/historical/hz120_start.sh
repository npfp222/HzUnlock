#!/system/bin/sh
pkill -f "Hz120 loop" 2>/dev/null
export CLASSPATH=/data/local/tmp/hz120.dex
setsid nohup app_process / Hz120 loop all > /data/local/tmp/hz120.log 2>&1 < /dev/null &
