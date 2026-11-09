# 公共设置。电视地址可用环境变量覆盖：TV=192.168.x.x:5555 ./scripts/install.sh
TV="${TV:-192.168.50.171:5555}"
PKG=com.family.tvquiz
SVC="$PKG/$PKG.GuardService"
LISTENER="$PKG/$PKG.MediaListener"
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BACKUP_DIR="$ROOT/backups"
a() { "$ADB" -s "$TV" "$@"; }
connect() {
  "$ADB" connect "$TV" >/dev/null
  a get-state >/dev/null 2>&1 || { echo "连不上电视 $TV，请检查网络和 ADB 授权"; exit 1; }
  echo "已连接 $TV ($(a shell getprop ro.product.model | tr -d '\r'))"
}
