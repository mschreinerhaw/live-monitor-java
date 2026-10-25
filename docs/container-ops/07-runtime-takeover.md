# Runtime OS 生产部署接管

用户已明确授权：本地启动 live-monitor，通过平台自动部署 Runtime OS 和依赖镜像，替换服务器 /opt 下的现有应用。接管不是展示测试页面，也不是由额外脚本绕开平台执行部署。

## 审计与实施边界

既有 Compose 栈有 API、MCP、资讯、编码服务、网络代理、网关、对象存储七个服务，保留当前端口、外部网络和业务数据。PostgreSQL、Redis、OpenSearch 为共享依赖，不盲目重建或覆盖数据；发布包包含其不可变镜像、健康探测、原始卷与启动定义，已有实例核验后复用，缺失的批准依赖可自动创建。

现有代码仅支持单容器发布，无法完整保留 service:api 网络命名空间、配置挂载和多服务依赖。因此新增独立应用栈模块，复用原认证、授权、异步任务、SSE、审计及数据库，不替换原有监控与单容器模块。

MCP 当前使用 Docker socket 执行 Python，并依赖主机 MAC 授权。默认仍禁止应用访问 Docker socket；迁移包只能在主机策略明确批准的原有服务上保留原绑定，不能由页面开启任意高权限挂载。已有编码网络隔离脚本作为经哈希批准的固定钩子执行，不接收页面 shell。

## 新增能力

- 应用栈配置只提交服务器、环境、包引用及 SHA256；含密钥的 Compose 与配置留在服务器私有目录。
- 主机策略核验发布包、配置文件、挂载范围、镜像 Digest、资源约束、命名空间和固定钩子。
- 发布前检查活跃任务与编码会话，拉取镜像，暂停写入，备份数据库和持久文件并校验，再重建业务服务。
- 配置回滚保留原数据，恢复旧镜像与部署参数；不执行数据库结构倒退。
- 多服务状态、资源、实时日志、逐服务停启、发布历史、异常事件和有界恢复均在平台内完成。
- 本地控制面独立持久化并保持运行；SSH 只作为受控 Agent 的加密传输，前端直接访问本地 live-monitor。

## 数据库与验证

新增 stack、stack_version、stack_snapshot 资源使用已有 ops_resource；没有删除旧表或修改业务数据库结构。

## 实际接管结果（2026-10-25，Asia/Shanghai）

**当前入口：http://127.0.0.1:18080/#/ops/stacks**。这是本地 Java 控制面直接提供的页面，不是 SSH 隧道连接的远程演示页。生产 Runtime OS 仍使用服务器原来的 18100/18101 入口。

从 Vue3 页面点击“备份并发布应用栈”，提交任务 **222bbaf8-712f-4a46-b105-3751e5b862b5**，最终 **SUCCEEDED**。API、MCP、资讯、编码、代理、网关、对象存储七个服务的容器 ID 均已变化；业务镜像内容 ID、三个业务 JAR 校验值、环境变量、持久挂载、只读配置与密钥内容、静态地址和网络命名空间均核验一致。权限与模板绑定四张表的指纹一致。其他既有容器保持原样。

| 项目 | 实际值 |
|---|---|
| 应用栈 / 环境 | runtime-os / production |
| 服务器 / SSH | 192.168.195.221 / 5221 |
| 发布包 | release-20261025080650 |
| 发布包 SHA256 | 2ab400c4e6aa7c0b0f84448e629979b0cee40b505910c1888449163de50c4611 |
| 受控发布文件 | /opt/live-monitor-managed-releases/agent-production/state/stack-rendered/2ab400c4e6aa7c0b0f84448e629979b0cee40b505910c1888449163de50c4611-compose.json |
| 原部署入口 | /opt/runtime-os-docker-stage/compose.yaml，现原子链接到上述受控文件 |
| 原入口备份 | agent-production/state/entrypoint-history/a18b1f60-4e58-4ec2-810e-aa4f7f1a3ea0/previous-compose.json |
| 数据备份 | /opt/live-monitor-managed-releases/agent-production/backups/stack-222bbaf8-712f-4a46-b105-3751e5b862b5 |
| 备份校验 | 约 12 GiB，19,395 个文件完整校验；含 PostgreSQL 逻辑归档；原数据未删除 |
| 共享依赖 | PostgreSQL、Redis、OpenSearch 原实例与数据保留，发布包固定对应镜像 Digest |
| 外部依赖 | 授权服务保留并持续探测，不冒充 Docker 容器计数 |
| 维护状态 | 已退出维护，自动采集恢复 |

首次接管用于迁移部署控制权，业务二进制保持原版本，避免把部署接管与未知业务升级混为一体。后续候选版本可以由构建流水线提供不可变镜像映射生成新发布包，再从平台发起真实升级。

## 持续监控与联调修复

重启本地控制面后，原任务、版本、加密凭据和数据仍可读取，后台 system 身份的 stack_probe 成功完成。七个业务服务、三个 Docker 依赖和一个外部授权服务健康；真实 API 日志已从应用栈页面成功跟踪。主机普通采集只返回该环境的受管容器资源，避免跨环境资源列表泄露。

人工停机意图在平台和 Agent 双层保存；健康抖动、退避、上限和镜像漂移均限制恢复。只读日志请求不阻断后台采样，发布、备份等写操作继续与采样互斥。SSE 生命周期与长任务匹配，避免正常备份两分钟后触发超时日志。

专用 Agent 身份禁止旧 Shell 命令，其凭据宿主保持启用，但自动生成的旧 Shell 采集项已停用；Docker 与应用栈指标由受控 Agent 采集。旧 SSH 解析器遇到未知磁盘输出时的空值拆箱异常已修复并增加回归测试，未把未知磁盘数错误写为零。

Windows 原构建目录出现 JAR 占用，改用独立 container.build.dir 完成构建并逐字节核验 26 个前端输出。启动脚本校验 JAR SHA256、确认端口属于刚启动的进程、保留稳定加密密钥并初始化随机管理员密码。初始密码登录拒绝的隔离启动实测通过。

## 验收证据

最终交付构建通过：Java 149 项、Vue 11 项、Agent 39 项测试；真实隔离 Compose 验收 1 项通过，浏览器 14 项检查通过且没有页面脚本错误。运行 JAR 内的 26 个前端文件与 Vue 构建逐字节一致。本地控制面重启后，自动任务 `70db68c2-3c33-489e-8812-f5e353275ff4` 再次确认生产应用栈健康。实际物理服务器验收为一台，不扩称多物理服务器验收完成。

最终测试摘要与原始证据在 [production-evidence](production-evidence/)：

- [Vue 页面发起的生产部署任务](production-evidence/deployment-task.json)
- [替换、挂载、配置、授权指纹核验](production-evidence/takeover-verification.json)
- [隔离真实应用栈故障与回滚验证](production-evidence/isolated-stack.json)
- [十个页面、窄屏、真实日志、自动采集检查](production-evidence/browser.json)
- [构建与测试摘要](production-evidence/summary.json)

生产实例没有注入故障。失败版本回退、持续 unhealthy 恢复、人工停机与命名空间代理重建在独立项目实测；生产实例验证真实发布、数据保护、可用性、自动采集和日志。PostgreSQL 生产迁移备份完成归档读取及校验，没有把它称为已经执行过生产库灾难恢复。

## 本地运行与后续发布

后台 Java 进程独立于浏览器和本次聊天运行。存储目录为仓库忽略的 `target/runtime-os-production-control`；仅登录信息在 `login-private.json`，完整加密身份在 `secrets-private.json`，均限制本机文件 ACL，不提交 Git。

重启时先通过记录的 PID 核实并停止自己的 Java 进程，保留整个存储目录，再运行：

```powershell
./scripts/start-local-ops-platform.ps1 -Artifact YOUR_VERIFIED_JAR -ExpectedSha256 YOUR_VERIFIED_SHA256
```

首次启动可自动完成本地账号初始化；加密密钥文件遗失时拒绝给已有数据生成新身份。生产的独立不健康恢复依赖本地控制面常驻，服务器 Docker 原生重启策略同时继续兜底。

新版本流程：构建与业务测试 → 向批准仓库发布不可变镜像 → 在服务器用 Runtime OS 仓库的 `prepare-live-monitor-release.py --candidate-images-file ...` 生成新包（仅准备与镜像校验，不重启业务）→ 页面编辑包引用及 SHA256 → 部署前检查 → 明确确认数据兼容与维护窗口 → 备份并发布 → 查看健康、日志、任务和版本记录。旧包与授权策略保留，准备新包不会覆盖旧包。业务专属探测、空闲检查和数据契约放在发布契约中，不写死到 Vue 或通用 Java/Agent 逻辑。

准备与核验工具已安装在服务器 `/opt/live-monitor-managed-releases/tools/`。禁止继续使用旧人工脚本绕过平台推进业务版本；`compose.yaml` 已是平台管理入口。所有 Root 配置、密钥和真实业务日志留在私有目录，公开证据只含校验值、任务和状态。
