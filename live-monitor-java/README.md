# Live Monitor Java

面向内部监控平台长期扩展的 Java Maven 工程。当前版本保持原静态前端不变，由 Spring Boot 托管 HTML/JS/ECharts 页面，并提供兼容原 `/api/...` 的后端接口。

## 技术栈

- 前端：HTML + JS + ECharts
- 后端：Java 8 + Spring Boot 2.7
- 数据库：SQLite，后续可切 MySQL
- ORM：MyBatis
- 定时任务：Spring Scheduler
- SSH：JSch
- Redis 检测：Lettuce
- ZooKeeper 检测：四字命令 / 端口探测
- 数据库检测：MySQL / Oracle / PostgreSQL / 通用 JDBC
- Web 检测：OkHttp

## 结构

```text
live-monitor-java/
├─ pom.xml
├─ src/main/java/com/live/monitor/
│  ├─ controller/
│  ├─ service/
│  │  ├─ RedisMonitorService.java
│  │  ├─ ZookeeperMonitorService.java
│  │  ├─ WebMonitorService.java
│  │  ├─ HostMonitorService.java
│  │  └─ SshService.java
│  ├─ scheduler/
│  ├─ entity/
│  ├─ mapper/
│  ├─ alert/
│  └─ config/
└─ src/main/resources/
   ├─ application.yml
   ├─ schema.sql
   └─ static/
```

## 启动

```bash
cd live-monitor-java
mvn spring-boot:run
```

启动后访问：

```text
http://127.0.0.1:8000/
http://127.0.0.1:8000/dashboard
http://127.0.0.1:8000/services/new
http://127.0.0.1:8000/alerts/settings
```

SQLite 数据库默认写入：

```text
live-monitor-java/data/live_monitor.db
```

## SSH 密码

主机表不保存明文密码。`host_config` 中保存的是：

```text
ssh_password_cipher
private_key_cipher
```

生产环境请设置固定密钥：

```bash
set LIVE_MONITOR_SECRET_KEY=your-long-random-secret
```

如果后续多实例部署，要保证各实例使用同一个 `LIVE_MONITOR_SECRET_KEY`，否则历史密文无法解密。

## 通用 JDBC 驱动

除内置 MySQL、Oracle、PostgreSQL 外，添加服务时可选择“通用 JDBC”。将对应数据库的 JDBC 驱动 jar 放到运行目录的 `lib/` 下，页面填写驱动类、JDBC 连接串、用户、密码、检测 SQL 和期望关键字即可。检测会执行 SQL，并在返回结果文本中查找期望关键字。

内置 MySQL 检测默认兼容 MySQL 5.x 和 MySQL 8.x：连接参数会显式设置超时、字符集、禁用 SSL 握手。默认使用项目内置 MySQL 8 驱动；如果要连旧版 MySQL，可把 MySQL 5.x 驱动 jar 放到 `lib/` 下，并在页面 JDBC 驱动类填写 `com.mysql.jdbc.Driver`。MySQL 8 可填写 `com.mysql.cj.jdbc.Driver` 或留空。

MySQL、Oracle、PostgreSQL 也支持可选的 JDBC 驱动类配置。填写后系统会优先从运行目录 `lib/` / `libs/` 下加载对应驱动，适合连接只能使用旧版驱动的数据库。

## 已提供接口

- 服务监控：`/api/services`、`/api/services/{id}/check`、`/api/dashboard`
- 告警配置：`/api/alert-policies`、`/api/alert-channels`、`/api/alert-groups`
- 告警记录：`/api/alerts`、`/api/services/{id}/alerts`
- 主机配置：`/api/hosts`
- 主机指标：`/api/hosts/{id}/metrics`
- 进程探测：`/api/hosts/{id}/processes`、`/api/hosts/{id}/process-status`

## 后续建议

- 将告警发送从“记录入库”扩展为邮件、短信、Webhook、钉钉发送器。
- 主机监控结果单独入库，形成趋势图。
- 增加登录、角色、操作审计。
- 数据量上来后把 SQLite 切换为 MySQL。
