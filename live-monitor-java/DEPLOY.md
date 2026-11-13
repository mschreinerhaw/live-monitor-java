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

## 1.0.1 告警可靠性升级

1. 停止旧进程，离线备份整个 data/ 以及配置和加密密钥；保留可恢复的旧版程序。
2. 使用本次发布包替换程序，保留原 H2、RocksDB 数据目录与密钥。首次启动通过 schema-h2.sql/schema-mysql.sql 创建新增任务、投影、尝试记录和手动重试审计表，不覆盖旧历史。
3. 在服务编辑页确认告警已启用、告警组和渠道已启用、接收人完整；DOWN 阈值设为 1 可首次通知，UNKNOWN 使用独立阈值，恢复使用连续成功次数。
4. 告警设置页“告警发送任务”显示 PENDING / SENDING / SUCCESS / FAILED / FINAL_FAILED。失败自动重试，最终失败可由 admin 手动重试。任务 ID、事件 ID 与每次尝试可追踪发送过程。
5. 实例崩溃时，PENDING/FAILED 随 Worker 启动继续处理；SENDING 在默认 5 分钟租约到期后恢复。进程关闭不删除未完成任务。历史补写失败会保留投影记录自动重试。
6. 完成任务与尝试默认保留 90 天；未完成任务及其检查快照不会被保留清理删除。积压超过 1000 条写警告日志，页面显示状态数量和最早未完成时间。
7. H2 切 MySQL 的迁移包含检查事件、告警状态、任务、尝试和重试审计；先备份，再在预生产验证目标 MySQL 版本和字符集。不得直接丢弃旧库中未完成的任务。
8. 回滚旧程序前先停止新进程并备份新表。旧程序不会消费新 Outbox；必须安排回放或迁移，不能通过删除表完成回滚。

验证命令为 mvn verify。真实 SMTP、短信、钉钉、企业微信需在部署环境配置正式凭据验证接收；本次自动化测试覆盖本地协议模拟、HTTP 网关、H2 持久化和强杀进程恢复，不代表真实第三方始终可达。

## Vue3 分支页面对照与构建

feature/vue3-frontend 已合入告警可靠性修复并补齐页面。完整对照与验证说明见 VUE3_FUNCTIONAL_PARITY.txt，前端操作与浏览器回归命令见 frontend/README.md。
执行 mvn verify 会安装锁定依赖、构建并同步 Vue、运行 Java 验证和打包；运行发布 JAR 不需要 Node。只在已构建前端时使用 -DskipFrontend=true。Vue 入口为生成文件，不能直接用旧 HTML 首页代替。
