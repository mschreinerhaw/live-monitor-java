#!/usr/bin/env bash
set -euo pipefail

APP_NAME=${APP_NAME:-live-monitor-java}
BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
LOG_DIR="${BASE_DIR}/logs"
PID_FILE="${LOG_DIR}/${APP_NAME}.pid"
STOP_TIMEOUT=${STOP_TIMEOUT:-30}

if [ ! -f "${PID_FILE}" ]; then
  echo "${APP_NAME} is not running: pid file not found"
  exit 0
fi

PID=$(cat "${PID_FILE}")
if [ -z "${PID}" ] || ! kill -0 "${PID}" >/dev/null 2>&1; then
  rm -f "${PID_FILE}"
  echo "${APP_NAME} is not running"
  exit 0
fi

echo "Stopping ${APP_NAME}, pid=${PID}"
kill "${PID}"

for _ in $(seq 1 "${STOP_TIMEOUT}"); do
  if ! kill -0 "${PID}" >/dev/null 2>&1; then
    rm -f "${PID_FILE}"
    echo "${APP_NAME} stopped"
    exit 0
  fi
  sleep 1
done

echo "Force stopping ${APP_NAME}, pid=${PID}"
kill -9 "${PID}" >/dev/null 2>&1 || true
rm -f "${PID_FILE}"
echo "${APP_NAME} stopped"
