#!/bin/zsh
# 撤销：关闭本应用的权限并卸载。只移除本应用，不影响 Aerial 和原厂设置。
set -e
source "$(dirname "$0")/common.sh"
connect
cur=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
new=$(echo "$cur" | tr ':' '\n' | grep -v -x "$SVC" | grep -v -x "$PKG/.GuardService" | paste -sd ':' -)
if [[ -z "$new" ]]; then
  a shell settings delete secure enabled_accessibility_services
  a shell settings put secure accessibility_enabled 0
else
  a shell settings put secure enabled_accessibility_services "$new"
fi
a shell cmd notification disallow_listener "$LISTENER" || true
a uninstall "$PKG" || true
echo "已撤销。当前无障碍服务: $(a shell settings get secure enabled_accessibility_services | tr -d '\r')"
