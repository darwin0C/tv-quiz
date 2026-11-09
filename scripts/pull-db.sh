#!/bin/zsh
# 把电视上的数据库复制到本机 backups/，便于核对流水（debug 版可用）。
set -e
source "$(dirname "$0")/common.sh"
connect
mkdir -p "$BACKUP_DIR"
out="$BACKUP_DIR/tvquiz-$(date +%Y%m%d-%H%M%S).db"
a exec-out run-as "$PKG" cat databases/tvquiz.db > "$out"
a exec-out run-as "$PKG" cat databases/tvquiz.db-wal > "$out-wal" 2>/dev/null || true
echo "已保存 $out"
