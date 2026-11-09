#!/bin/zsh
# 授予测试应用需要的三项权限。只追加本应用，不改动其他已开启的服务。
set -e
source "$(dirname "$0")/common.sh"
connect
cur=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ -z "$cur" || "$cur" == "null" ]]; then new="$SVC"
elif [[ ":$cur:" == *":$SVC:"* ]]; then new="$cur"
else new="$cur:$SVC"; fi
a shell settings put secure enabled_accessibility_services "$new"
a shell settings put secure accessibility_enabled 1
a shell cmd notification allow_listener "$LISTENER"
a shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
a shell appops set "$PKG" GET_USAGE_STATS allow
echo "无障碍服务: $(a shell settings get secure enabled_accessibility_services | tr -d '\r')"
echo "通知使用权: $(a shell settings get secure enabled_notification_listeners | tr -d '\r')"
echo "使用情况:   $(a shell appops get "$PKG" GET_USAGE_STATS | tr -d '\r')"
echo "悬浮窗:     $(a shell appops get "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r')"
