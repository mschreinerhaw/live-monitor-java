#!/usr/bin/env bash
set -euo pipefail

APP_NAME=${APP_NAME:-live-monitor-java}
BASE_DIR=$(cd "$(dirname "$0")/.." && pwd)
LIB_DIR="${BASE_DIR}/lib"
CONFIG_DIR="${BASE_DIR}/config"
LOG_DIR="${BASE_DIR}/logs"
DATA_DIR="${BASE_DIR}/data"
PID_FILE="${LOG_DIR}/${APP_NAME}.pid"
STDOUT_LOG="${LOG_DIR}/console.log"
JAR_FILE="${JAR_FILE:-${LIB_DIR}/${APP_NAME}.jar}"

JAVA_CMD=${JAVA_CMD:-java}
if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
  JAVA_CMD="${JAVA_HOME}/bin/java"
fi

JAVA_OPTS=${JAVA_OPTS:-"-Xms256m -Xmx512m -Dfile.encoding=UTF-8"}
SPRING_OPTS=${SPRING_OPTS:-}

mkdir -p "${LOG_DIR}" "${DATA_DIR}"

if [ ! -f "${JAR_FILE}" ]; then
  echo "Jar not found: ${JAR_FILE}"
  exit 1
fi

if [ -f "${PID_FILE}" ]; then
  PID=$(cat "${PID_FILE}")
  if [ -n "${PID}" ] && kill -0 "${PID}" >/dev/null 2>&1; then
    echo "${APP_NAME} is already running, pid=${PID}"
    exit 0
  fi
  rm -f "${PID_FILE}"
fi

cd "${BASE_DIR}"
umask 027

nohup "${JAVA_CMD}" ${JAVA_OPTS} -jar "${JAR_FILE}" \
  --spring.config.additional-location="optional:file:${CONFIG_DIR}/" \
  --logging.file.name="${LOG_DIR}/${APP_NAME}.log" \
  ${SPRING_OPTS} >> "${STDOUT_LOG}" 2>&1 &

PID=$!
echo "${PID}" > "${PID_FILE}"
echo "${APP_NAME} started, pid=${PID}"
echo "logs: ${LOG_DIR}"
