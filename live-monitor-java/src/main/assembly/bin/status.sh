#!/usr/bin/env bash
set -euo pipefail

APP_NAME=${APP_NAME:-live-monitor-java}
BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
PID_FILE="${BASE_DIR}/logs/${APP_NAME}.pid"

if [ ! -f "${PID_FILE}" ]; then
  echo "${APP_NAME} is stopped"
  exit 3
fi

PID=$(cat "${PID_FILE}")
if [ -n "${PID}" ] && kill -0 "${PID}" >/dev/null 2>&1; then
  echo "${APP_NAME} is running, pid=${PID}"
  exit 0
fi

echo "${APP_NAME} is stopped, stale pid file: ${PID_FILE}"
exit 1
