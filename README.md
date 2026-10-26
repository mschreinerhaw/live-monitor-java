# Live Monitor — 轻量级服务与主机监控平台

Live Monitor 是一个面向内部运维场景的监控项目，用于管理被监控服务、执行周期性健康检查、查看运行状态及历史记录，并配置告警渠道。仓库同时保留 **Java 主工程** 与较早的 **Python 实现**。

> **当前代码说明**：默认推荐从 `live-monitor-java/` 启动 Java 工程。本文件依据仓库 `main` 分支当前源码编写；具体功能应以对应分支的实现为准。仓库中另有旧版说明，部分数据库描述已不符合当前 Java 配置。

## 功能概览

- **监控面板**：服务状态汇总、监控检查结果与趋势展示。
- **服务探测**：Web/HTTP、Redis、ZooKeeper 等服务检查，代码中还包含数据库与端口检查相关实现。
- **主机监控**：主机配置、资源指标、进程状态及 SSH 相关操作。
- **告警管理**：告警策略、告警组、告警记录及邮件、短信、Webhook、钉钉、企业微信等渠道的发送实现；实际发送需要正确配置相应渠道。
- **登录保护**：Java 应用使用 HTTP Session 处理登录态，对受保护的页面与 API 进行拦截。
- **数据迁移**：包含将旧 SQLite 数据迁移至 H2 与 RocksDB 的迁移服务；迁移操作前应先备份数据。

## 项目结构

```text
.
├── live-monitor-java/                # Java / Spring Boot 主工程
│   ├── pom.xml
│   ├── DEPLOY.md                     # Java 发布包与启停说明
│   ├── README.md                     # Java 子项目介绍（部分内容较旧）
│   └── src/main/
│       ├── java/com/live/monitor/
│       │   ├── controller/          # HTTP API 与页面路由
│       │   ├── service/             # 检测及业务逻辑
│       │   ├── scheduler/           # 定时监控任务
│       │   ├── alert/               # 告警逻辑与发送
│       │   ├── mapper/              # MyBatis 数据访问
│       │   ├── config/              # 配置、鉴权、数据迁移
│       │   └── store/               # RocksDB 历史数据
│       └── resources/
│           ├── application.yml
│           ├── schema.sql
│           └── static/              # HTML、JavaScript、ECharts
└── live-monitor/                     # Python 版本及其前端、测试、Docker 配置
    ├── backend/
    ├── frontend/
    ├── docker/
    ├── scripts/
    └── README.md
```

## 技术栈

| 组成 | 当前 Java 工程实现 |
| --- | --- |
| 后端 | Java 8、Spring Boot 2.7.18 |
| 构建 | Maven |
| Web 界面 | HTML、JavaScript、ECharts、Lucide |
| 数据访问 | MyBatis |
| 当前默认配置库 | H2 文件数据库 |
| 历史记录存储 | RocksDB |
| 旧版本数据兼容 | SQLite 迁移逻辑 |
| 探测与连接 | OkHttp、Lettuce、JSch 等 |
| 任务调度 | Spring Scheduler |

**注意：** 仓库根目录当前未包含 Vue 3 工程；本 README 不将规划中的 Vue 3 容器运维平台、自动部署/备份/异常拉起等能力描述为已交付功能。

## 快速开始（Java）

前提条件：已安装 JDK 8 或兼容的更高版本、Maven；运行用户对 `data/` 和 `logs/` 目录应有写权限。

```bash
git clone https://github.com/mschreinerhaw/live-monitor-java.git
cd live-monitor-java/live-monitor-java

# 建议在启动前设置固定的高强度密钥
export LIVE_MONITOR_SECRET_KEY="replace-with-a-long-random-secret"

mvn spring-boot:run
```

Windows PowerShell 可使用：

```powershell
$env:LIVE_MONITOR_SECRET_KEY = "replace-with-a-long-random-secret"
mvn spring-boot:run
```

默认服务端口为 **8000**。启动后可访问：

- 登录页：`http://127.0.0.1:8000/login.html`
- 监控面板：`http://127.0.0.1:8000/dashboard`
- 主机管理：`http://127.0.0.1:8000/hosts`
- 告警配置：`http://127.0.0.1:8000/alerts/settings`
- 健康检查：`http://127.0.0.1:8000/api/health`

除公共接口外，相关 API 需要有效登录会话。登录账户来源和初始化方式应检查部署环境中的用户数据与初始化脚本，不应假定存在通用默认账号。

## 默认数据与配置

配置文件：`live-monitor-java/src/main/resources/application.yml`。

| 路径 | 用途 |
| --- | --- |
| `./data/h2/live_monitor` | H2 文件库，保存主要业务配置 |
| `./data/rocksdb` | RocksDB 历史记录 |
| `./data/live_monitor.db` | 旧 SQLite 数据来源路径（用于兼容迁移） |
| `./data/backup` | SQLite 迁移备份路径 |
| `./logs/live-monitor-java.log` | 应用日志 |

这些相对路径以**进程工作目录**为基准。生产环境应备份 H2 与 RocksDB 数据目录，并验证恢复流程；不要在运行中直接复制数据库目录作为唯一备份手段。

## 构建与发布

```bash
cd live-monitor-java
mvn clean test
mvn clean package
```

项目配置了 Maven Assembly 发布包。可查看 `live-monitor-java/DEPLOY.md`，了解发布包目录、Linux / Windows 启停脚本和日志路径。

**配置差异提醒：** `DEPLOY.md` 的部分旧示例仍展示 SQLite 数据源，而当前 `application.yml` 的默认数据源是 H2。实际发布时应以**打包产物中最终生效的配置**为准，尤其核对数据源、路径和环境变量。

## 主要接口

| 类型 | 示例路径 |
| --- | --- |
| 用户会话 | `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/me` |
| 服务列表和检测 | `/api/services`、`/api/services/{id}/check` |
| 监控面板 | `/api/dashboard` |
| 主机管理 | `/api/hosts` |
| 资源指标 | `/api/hosts/{id}/metrics` |
| 进程状态 | `/api/hosts/{id}/process-status` |
| 告警配置 | `/api/alert-policies`、`/api/alert-channels`、`/api/alert-groups` |
| 告警记录 | `/api/alerts` |

具体请求方法、参数及响应结构以 `controller/` 实现为准。

## 安全与生产注意事项

1. **不要直接将默认配置暴露到公网。** 部署时应启用 HTTPS、访问控制，并避免对外暴露不必要的运维接口。
2. **必须配置 `LIVE_MONITOR_SECRET_KEY`。** 不要使用配置文件里的开发默认值；密钥需妥善保管，变更前评估已有加密数据的解密影响。
3. **注意当前密码验证实现。** Java `AuthService` 当前使用直接字符串比较验证用户密码，不能视为具备现代密码哈希保护；正式生产前应改为 BCrypt/Argon2 等强哈希并设计兼容迁移。
4. **审查告警渠道和 SSH 凭据的存储、权限及日志输出。** 不要将真实凭据提交至仓库。
5. **生产发布前进行完整测试。** 包括鉴权、迁移、备份恢复、告警发送、异常处理和服务重启后的数据一致性。

## 历史 Python 工程

`live-monitor/` 目录保留 Python 后端、静态前端及 Docker Compose 相关文件，使用方式请参阅 [Python 子项目说明](live-monitor/README.md)。该实现与 Java 主工程不应混用数据库配置、启动命令和部署说明。

## 相关文档

- [Java 子项目](live-monitor-java/README.md)
- [Java 生产部署文档](live-monitor-java/DEPLOY.md)
- [Python 子项目](live-monitor/README.md)
- [早期设计文档](live-monitor/Live-monitor设计文档.md)

---

本仓库 README 仅说明已经能够从当前代码和配置核实的能力；拟新增的容器镜像部署、备份、异常检测、自动拉起及 Vue 3 管理界面应在实现并验证后补充文档。
