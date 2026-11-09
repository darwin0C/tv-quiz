#!/bin/zsh
# 打包并安装到电视，然后授权。会先备份当前的无障碍设置。
set -e
source "$(dirname "$0")/common.sh"
cd "$ROOT"
./gradlew -q assembleDebug
connect
mkdir -p "$BACKUP_DIR"
STAMP=$(date +%Y%m%d-%H%M%S)
a shell settings get secure enabled_accessibility_services | tr -d '\r' > "$BACKUP_DIR/a11y-$STAMP.txt"
a shell settings get secure enabled_notification_listeners | tr -d '\r' > "$BACKUP_DIR/listeners-$STAMP.txt"
echo "已备份当前设置到 backups/*-$STAMP.txt"
a install -r app/build/outputs/apk/debug/app-debug.apk
"$(dirname "$0")/grant.sh"
