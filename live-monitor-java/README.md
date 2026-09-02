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
mvn clean -DskipTests package
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

## 主机监控命令依赖

后端主机监控通过 SSH 登录目标 Linux 主机，并执行系统命令采集 CPU、内存、磁盘和负载指标。目标主机需要具备以下常用命令或文件：

- CPU 使用率：`awk`、`/proc/stat`
- 系统负载：`awk`、`/proc/loadavg`
- 内存使用率和总量：`awk`、`/proc/meminfo`
- CPU 核数：优先使用 `nproc`，缺失时回退读取 `/proc/cpuinfo`
- 已挂载磁盘：`df -P -T -B1`
- 物理磁盘数量：优先使用 `lsblk`，缺失时依次回退 `/proc/partitions`、`fdisk -l`

其中 `awk`、`df`、`grep`、`cat` 通常由 Linux 基础系统提供。`lsblk` 用于提升物理磁盘识别准确性；如果目标系统没有 `lsblk`，仍可通过回退命令获取部分磁盘信息。部分发行版执行 `fdisk -l` 可能需要更高权限，权限不足时只会影响物理磁盘数量兜底识别，不影响 CPU、内存、负载和已挂载磁盘采集。

建议用于主机监控的 SSH 账号至少具备读取 `/proc/stat`、`/proc/loadavg`、`/proc/meminfo`、`/proc/cpuinfo`、`/proc/partitions` 以及执行 `df`、`lsblk` 的权限。常规 CPU、内存和挂载磁盘监控不需要 sudo。

## 告警模板自定义

告警正文模板默认从运行目录 `./templates` 读取，可通过配置项 `live-monitor.template-dir` 修改模板目录。外部目录中存在同名模板时优先使用外部文件；不存在时回退使用项目内置的 `src/main/resources/templates/*.j2`。

模板支持简单的 Jinja 风格变量，例如 `{{ serviceName }}`、`{{ alertTime }}`。常用变量包括：

- 通用服务变量：`serviceName`、`instanceName`、`host`、`level`、`alertTime`、`recoverTime`、`duration`、`responseTime`、`errorMsg`、`alertReason`、`recoverReason`
- 主机资源变量：`cpu`、`memory`、`disk`、`cpuText`、`memoryText`、`diskText`、`alertItems`、`recoverItems`、`alertSummary`
- 数据库断言变量：`databaseProduct`、`databaseResult`、`databaseRule`、`databaseHit`、`databaseReason`、`databaseSummary`、`businessImpact`、`actionSuggestion`

当前模板文件对应关系：

- 短信服务告警：`sms_service_alert.j2`、`sms_service_recover.j2`
- 短信数据库断言告警：`sms_database_assertion_alert.j2`
- 邮件服务和主机告警：`email_service_alert.j2`、`email_host_resource_alert.j2`、`email_host_resource_recover.j2`
- 邮件数据库断言告警：`email_database_assertion_alert.j2`
- 主机短信/普通告警：`alert_host_resource.j2`、`alert_host_resource_recover.j2`
- HTTP、钉钉、企业微信告警：`http_service_alert.j2`、`http_service_recover.j2`、`http_host_resource_alert.j2`、`http_host_resource_recover.j2`、`http_database_assertion_alert.j2`

HTTP 告警的请求体可以在告警渠道页面自由配置，适合钉钉、企业微信等 Webhook。请求体中可使用 `${message}`、`${content}`、`{{message}}`、`{{content}}`，系统会将它们替换为对应 `http_*.j2` 模板渲染后的告警正文。例如：

```json
{
  "msgtype": "text",
  "text": {
    "content": "${message}"
  }
}
```

钉钉和企业微信内置渠道也会复用 `http_*.j2` 模板生成消息正文；如需调整机器人收到的告警文案，修改对应 HTTP 模板即可。

## 通用 JDBC 驱动

除内置 MySQL、Oracle、PostgreSQL 外，添加服务时可选择“通用 JDBC”。将对应数据库的 JDBC 驱动 jar 放到运行目录的 `lib/` 下，页面填写驱动类、JDBC 连接串、用户、密码、检测 SQL 和期望关键字即可。检测会执行 SQL，并在返回结果文本中查找期望关键字。

内置 MySQL 检测默认兼容 MySQL 5.x 和 MySQL 8.x：连接参数会显式设置超时、字符集、禁用 SSL 握手。默认使用项目内置 MySQL 8 驱动；如果要连旧版 MySQL，可把 MySQL 5.x 驱动 jar 放到 `lib/` 下，并在页面 JDBC 驱动类填写 `com.mysql.jdbc.Driver`。MySQL 8 可填写 `com.mysql.cj.jdbc.Driver` 或留空。

MySQL、Oracle、PostgreSQL 也支持可选的 JDBC 驱动类配置。填写后系统会优先从运行目录 `lib/` / `libs/` 下加载对应驱动，适合连接只能使用旧版驱动的数据库。

## 高级响应断言规则使用方法

Web/API 监控和数据库断言支持高级响应断言规则。添加或编辑服务时，在断言配置区域可以选择“可视化配置”或“高级表达式”：可视化配置会自动生成表达式；高级表达式适合直接编写复杂规则。

规则表达式最终返回 true/false，返回 false 时检测结果为 DOWN。表达式支持 `&&` / `and`、`||` / `or`、`!` / `not` 和括号组合，比较操作符支持 `==`、`!=`、`>`、`>=`、`<`、`<=`。

常用函数：

- `status()`：HTTP 状态码，例如 `status() == 200`
- `responseMs()` / `time()`：响应时间，单位毫秒，例如 `responseMs() < 3000`
- `body()`：完整响应正文
- `contains("文本")`：响应正文包含文本
- `contains(目标, "文本")`：指定目标包含文本，例如 `contains(json("$.message"), "success")`
- `icontains(目标, "文本")`：忽略大小写包含
- `notContains(目标, "文本")`：不包含
- `json("$.path")`：从 JSON 响应中取值，支持对象字段和数组下标，例如 `json("$.data.rows[0].code")`
- `regex("正则")`：从响应正文中提取正则命中内容；有捕获组时取第一个捕获组，没有捕获组时取完整命中
- `matches("正则")`：判断响应正文是否匹配正则
- `exists(目标)`：判断目标值存在且非空
- `number(目标)`、`string(目标)`：将目标值按数字或文本参与比较

Web/API 监控示例：

```text
status() == 200 && json("$.code") == 0 && responseMs() < 3000
contains("success") && icontains(json("$.status"), "ok")
regex("CPU:\\s*([0-9.]+)") < 80
exists(json("$.data.token")) && notContains(body(), "error")
```

数据库高级断言复用同一套表达式，并额外支持按查询结果字段取值。数据库查询结果会以表格行形式参与规则判断：

- `field("字段名")` / `column("字段名")`：读取查询结果字段值，例如 `field("FUND_CODE") == "011389"`
- `allRowsCompare("左字段", "操作符", "右字段")`：所有行的两个字段逐行比较
- `anyRowsCompare("左字段", "操作符", "右字段")`：任意一行满足字段比较
- `sameValues("A.字段", "B.字段")`：跨库断言中比较两个数据源字段值集合是否一致
- `absDiff(左值, 右值)`：数字绝对差
- `pctDiff(左值, 右值)`：数字差异比例

数据库断言示例：

```text
field("ORDER_COUNT") > 0
contains(field("FUND_NAME"), "创新") && field("FUND_CODE") == "011389"
allRowsCompare("FUND_CODE", "==", "fund_code")
sameValues("A.FUND_CODE", "B.FUND_CODE") && absDiff(field("A.AMOUNT"), field("B.AMOUNT")) <= 0.05
```

注意事项：规则长度最多 1000 字符；响应正文参与解析的最大长度为 262144 字符；JSON 路径深度最多 32 层；正则表达式最长 300 字符，正则匹配只读取响应正文前 8192 字符，并有 100ms 超时保护。带前导 0 的编号建议用引号按文本比较，例如 `json("$.FUND_CODE") == "011389"`。

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
