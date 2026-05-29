@echo off
setlocal

set APP_NAME=live-monitor-java
set BASE_DIR=%~dp0..
for %%I in ("%BASE_DIR%") do set BASE_DIR=%%~fI
set LIB_DIR=%BASE_DIR%\lib
set CONFIG_DIR=%BASE_DIR%\config
set LOG_DIR=%BASE_DIR%\logs
set DATA_DIR=%BASE_DIR%\data
set JAR_FILE=%LIB_DIR%\%APP_NAME%.jar

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

set JAVA_CMD=java
if defined JAVA_HOME set JAVA_CMD=%JAVA_HOME%\bin\java.exe

if not defined JAVA_OPTS set JAVA_OPTS=-Xms256m -Xmx512m -Dfile.encoding=UTF-8

cd /d "%BASE_DIR%"
start "%APP_NAME%" /B "%JAVA_CMD%" %JAVA_OPTS% -jar "%JAR_FILE%" --spring.config.additional-location=optional:file:%CONFIG_DIR%/ --logging.file.name="%LOG_DIR%\%APP_NAME%.log" %SPRING_OPTS% >> "%LOG_DIR%\console.log" 2>&1

echo %APP_NAME% started
echo logs: %LOG_DIR%
