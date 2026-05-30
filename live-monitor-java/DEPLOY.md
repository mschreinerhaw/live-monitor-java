# Live Monitor Java 生产发布说明

## 目录规范

发布包解压后目录如下：

```text
live-monitor-java-1.0.0/
├─ bin/      启动、停止、重启、状态脚本
├─ config/   外置配置文件 application.yml
├─ lib/      应用可执行 jar
├─ logs/     控制台日志、应用日志、pid 文件
└─ data/     SQLite 数据库文件
```

`config/application.yml` 可在生产环境直接修改。启动脚本会固定工作目录到发布根目录，因此默认 SQLite 路径 `./data/live_monitor.db` 会写入发布目录下的 `data/`。

## 打包

```bash
cd live-monitor-java
mvn clean package -DskipTests
```

产物：

```text
target/live-monitor-java-1.0.0-release.tar.gz
target/live-monitor-java-1.0.0-release.zip
```

## Linux 启停

```bash
tar -zxf live-monitor-java-1.0.0-release.tar.gz
cd live-monitor-java-1.0.0

bin/start.sh
bin/status.sh
bin/stop.sh
bin/restart.sh
```

常用环境变量：

```bash
export LIVE_MONITOR_SECRET_KEY="your-long-random-secret"
export JAVA_OPTS="-Xms512m -Xmx1024m -Dfile.encoding=UTF-8"
export SPRING_OPTS="--server.port=8000"
```

生产环境务必固定 `LIVE_MONITOR_SECRET_KEY`。该密钥用于 SSH 密码、私钥等敏感字段加解密；密钥变化后，历史密文将无法解密。

## Windows 启动

```bat
cd live-monitor-java-1.0.0
bin\start.bat
```

## 日志

```text
logs/console.log                 标准输出和标准错误
logs/live-monitor-java.log       Spring Boot 应用日志
logs/live-monitor-java.pid       Linux 启动脚本 pid 文件
```

## 配置示例

默认配置来自 `config/application.yml`：

```yaml
server:
  port: 8000

spring:
  datasource:
    driver-class-name: org.sqlite.JDBC
    url: jdbc:sqlite:./data/live_monitor.db
    hikari:
      maximum-pool-size: 1
      minimum-idle: 1
      connection-timeout: 30000
      connection-init-sql: PRAGMA busy_timeout=30000

live-monitor:
  secret-key: ${LIVE_MONITOR_SECRET_KEY:change-this-dev-key}
```

修改配置后执行：

```bash
bin/restart.sh
```
