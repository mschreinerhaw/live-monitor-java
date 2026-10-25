# Runtime OS 一键交付安装

目标是新客户环境的完整安装。运维界面仍只有镜像管理、容器管理、基础监控；在镜像管理点击“安装 Runtime OS”，依次上传正式安装包、确认必要配置、检查并授权、查看安装结果。后台执行镜像导入、依赖启动、数据库初始化、应用健康检查与监控登记。不从镜像名称推测依赖，不执行上传包提供的主机 Shell 命令。

安装完成后进入容器管理，使用“启动整套 / 停止整套”管理整套 Runtime OS；组件行保留独立启动、停止、重启与日志按钮。整套停止会记录人工停机意图，保留数据并继续采集，不会触发异常拉起。

## 安装包与制作

安装包是带签名的 `.install.tar.gz`，不同于普通 `docker save` 镜像包。根目录包含 `installer.json`、`installer.sig` 及清单明确声明的镜像和模板文件。协议 `live-monitor.install.v1` 声明产品、版本、镜像内容 ID、每个文件 SHA256、参数、Compose 配置、资源需求、初始化资产、健康检查和访问入口。所有镜像由不可变配置 ID 引用。

发行流水线先在产品仓库运行 `scripts/build-runtime-os-installer.py`，从受版本管理的 Compose、数据库空库 schema 和公共配置生成定义；再用本仓库 `scripts/build-install-bundle.py` 导出显式指定的镜像、计算摘要并签名。签名私钥作为 CI 外部输入，禁止放入发行包。不得从现网 Compose、环境文件或数据库导出客户配置、数据、模型密钥和许可证。

```bash
python3 build-runtime-os-installer.py --repository /build/Langchain4j-Chatchat \
  --image-map /build/image-map.json --version RELEASE_VERSION --output-dir /build/definition
python3 build-install-bundle.py --definition /build/definition/installer-definition.json \
  --assets-dir /build/definition/assets --image-map /build/image-map.json \
  --signing-key /secure/release-signing.pem --output /release/runtime-os.install.tar.gz
```

镜像输入包括 API、MCP、News、Coding Agent、Nginx、PostgreSQL、Redis、OpenSearch 与 Python Runtime。当前标准定义启动九个长驻服务，Python 镜像供 MCP 沙箱按需使用。使用新的 PostgreSQL 数据目录初始化两个空库；默认 Python 环境引用包内镜像 ID。许可证及模型连接通过 Runtime OS 自身页面按客户授权配置，不拷贝其他现场的凭据。

## 一次性交付初始化

前提是 Linux/amd64 主机已安装 Docker 与 Compose，平台已通过固定命令 SSH Agent 连接该主机。这属于平台安装初始化，客户日常安装不需要打开控制台。首次安装 Agent 时通过可信交付渠道配置发行公钥、允许的产品及必要能力；不得信任上传包自带的公钥。随交付的 `deploy/container-platform/trust/runtime-os-release-20261025.pub` 是本次发行公钥。

```bash
python3 /release/ops/tools/install-agent-release.py --release /release/ops/agent --target /opt/live-monitor-agent \
  --installer-product runtime-os --trust-public-key /release/ops/container-platform/trust/runtime-os-release-20261025.pub \
  --installer-capability docker_socket --installer-capability host_identity \
  --installer-max-cpus 4 --installer-max-memory-mb 4096
```

平台发行包的 `ops/agent/agent-release.json` 包含 agent.py、stack_ops.py、installer.py 的 SHA256。源代码修改后，发行方须同步维护该清单；Agent Python 文件固定 LF 换行，避免跨系统打包改变摘要。公钥安装到 root 管理的信任目录。安装资源额度独立于原单容器操作额度，不为了安装大服务放宽普通容器策略。标准包需要约 16 GiB 主机内存、8 核及足够镜像、临时文件和数据空间；安装前还校验当前可用内存、磁盘、版本、地址和端口。主机发布接口由 Agent 既有 `publish_address` 决定，平台不自动修改防火墙。

## 执行与数据保护

- 大包流式传送、落盘和校验。HTTP 安装包上限默认 32 GiB，普通单镜像接口仍为 4 GiB；可以通过配置调整。归档禁止绝对路径、路径逃逸、链接与未声明文件。
- 主机验证签名、内容摘要、产品及能力授权，再生成配置和检查计划。资源、端口、可用网络地址均来自实际主机；检查不启动容器。
- 密码、内部认证与共享密钥按安装独立生成；内部认证使用 AES-GCM。任务请求与私有资源中的参数使用平台既有密钥加密，公开 API、安装结果和诊断下载均不返回参数密文的解密值。
- 用户明确确认后使用有效检查计划安装，执行前重新检查主机条件。签名 Compose 仅允许声明的镜像、隔离网络、有限资源以及新安装目录内的挂载；访问 Docker 和本机授权标识还需主机明确批准。
- PostgreSQL 等依赖完成自身初始化后才启动依赖它们的应用，健康检查不会把 Docker 的 starting 状态当作成功。所有组件就绪后才登记已有应用栈、版本和快照，沿用原后台采集、人工停机、退避和熔断恢复逻辑。
- 任务有幂等键；同一请求重发不会重新部署。页面关闭后保留任务引用，可重新打开向导查询结果。未知结果不会盲目重试；已完成远端安装可按同一计划恢复监控登记。
- 当前是新装流程，不接管已有项目，也不自动覆盖旧生产数据库。新安装失败时尝试停止本次项目，保留数据及失败组件诊断；停止失败明确要求人工核对，不宣称回滚数据库成功。

## 验证记录

2026-10-25 已完成服务器发布及完整安装验收，入口 `http://192.168.195.221:18080/#/ops/images`。点击“安装 Runtime OS”进入四步向导。完整安装在 `563a5a4` 验证，当前运行代码 `09f5763` 继续提供同一安装流程，并新增“启动整套 / 停止整套”快捷操作；产品安装定义在 `Langchain4j-Chatchat` 的 `feature/runtime-os-one-click-delivery`，提交 `1f7bdadd3`。控制面镜像为 `localhost:18443/live-monitor/platform@sha256:17951d6d8f7ed50fe7cf130ecd6c4e7d98e973ddf468b859aa33927d3e9a310e`，JAR SHA256 为 `95ac7fe2d3034da178f7b878fd6290b1eace5e874d71e87d32f5ff41402f3d5e`。

真实安装包 `runtime-os.install.tar.gz` 为 **3,155,899,546 字节**，SHA256 **`fc64ff1703ea6bfb9b80b64b1fc0de079f2207e8a80c521295673b011feb762c`**。本地交付文件在仓库 `target/installer-validation/`；服务器保留文件在 `/opt/live-monitor-production-migration/installer-validation/runtime-os-2026.10.25-final.install.tar.gz`。完整业务安装包单独交付，不嵌入平台约 135 MB 的 JAR。它包含九种镜像内容，启动九个长驻服务，其中 Nginx 镜像用于网关和 Node 代理；Python 镜像用于按需沙箱。

| 验证项 | 实际结果 |
| --- | --- |
| Java / Vue / Agent | 161 / 20 / 61 项测试通过，零失败；Vue、JAR、平台发行包构建通过 |
| 真实页面安装 | Edge 浏览器上传完整包、验签、配置 18200/18201、预检、确认安装，全流程成功 |
| 依赖与空库 | 九个服务运行；新 API 库 118 张表、MCP 库 63 张表，默认 Python 环境引用包内不可变镜像 ID |
| 访问与认证 | 两个主机 IP 入口可访问；全新 Runtime OS 管理员登录通过，不记录登录令牌 |
| 监控 | 九组件接入既有后台采集，CPU/内存可见；重开浏览器可恢复安装结果，无页面异常 |
| 异常拉起 | 仅对隔离安装的 gateway 进程注入异常退出，原生重启次数 0→1，4.62 秒恢复 HTTP 访问 |
| 幂等 | 重复提交同一安装请求返回同一任务，没有重复安装 |
| 整套启停 | 从容器页面点击“启动整套”，九组件恢复健康；点击“停止整套”后整体停机，持续采集且未自动重启 |
| 人工停机 | 平台停止整个隔离项目；继续观察 75 秒，九组件保持停止且标记人工停机 |
| 存量保护 | 38 个既有非目标容器 ID、镜像和启动时间未变化，原生产 Runtime OS 健康 |

验收项目 `lm-installer-acceptance-install-ee4c01acf8658e111766` 最终停止以释放资源，其数据与诊断保留。18080 的生产控制面继续服务原 Runtime OS。没有替换旧业务数据库或修改原业务容器。最新发布前控制面数据库冷备位于 `/opt/live-monitor-production-migration/installer-platform-09f5763/control-data-backup`。

验收证据见 [installer-evidence](installer-evidence/)。本次在同一物理服务器上的独立目录、网络和环境完成验证；尚未以另一台全新物理服务器验证所有防火墙、离线操作系统依赖及客户许可证。许可证、模型连接和客户业务任务需要在客户激活配置后另行验收。当前新装不提供覆盖既有安装的数据迁移或原地升级。

## 实施范围与文件

| 阶段 | 完成内容及主要文件 | 数据与验证 |
| --- | --- | --- |
| 分支与方案 | 保留现有 Vue3 `feature/container-ops-platform`；产品新增独立发行分支，均不修改 main | 原监控与简化导航保留 |
| 后端 | `InstallationService`、`OpsController`、`OpsTaskService`、`OpsAgentGateway`、`ImageArchiveService` | 沿用资源、异步任务、事务与加密；无平台数据库 DDL 变更 |
| Vue3 | `RuntimeInstallWizard.vue`、`RuntimeConsoleView.vue`、`api/ops.js`、`ops/installerModel.js` | 四步向导、任务恢复与安全诊断；20 项前端测试 |
| 安装引擎 | `agent/installer.py`、`agent.py`、`stack_ops.py` | 签名、摘要、环境校验、隔离新装；失败停机保留数据；61 项 Agent 测试 |
| 发行 | `build-install-bundle.py`、产品 `build-runtime-os-installer.py`、`install-agent-release.py`、`release.xml` | 产品依赖定义与平台执行器分离；公开公钥，不发布私钥 |
| 集成与交付 | 本手册及 `installer-evidence/` | 真实九服务安装、监控、异常恢复、人工停机与既有服务保护均已验证 |
