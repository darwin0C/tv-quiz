#!/bin/zsh
# 实时查看守门服务日志（前台切换、扣时、拦截）。Ctrl+C 退出。
source "$(dirname "$0")/common.sh"
connect
a logcat -v time -s TVQuiz
