# 分阶段实施记录

## 阶段一：审计与分支

完成：两个仓库 fetch/prune、干净工作区检查、通过实际 Vue 代码确认基线，建立 feature/container-ops-platform；无强推/生产分支修改。文件：01-audit.md。数据库：无。验证：分支与入口/依赖/路由/服务审计。剩余：后续实现、测试与交付。

## 阶段二：后端基础

完成：独立 /api/ops、复用会话/加密 SSH 主机凭据、主机/仓库/容器服务、固定 SSH 公钥代理、资源授权和审计。Java 运维模块在 src/main/java/com/live/monitor/ops，代理在 agent/agent.py。数据库：V001 新建 ops_resource、ops_task、ops_audit、ops_lock、ops_migration，不改旧表。测试：权限/范围、参数/不可变镜像、乐观锁、主机互斥、真实 Spring 代理上下文测试通过。剩余：前端与异步编排。

## 阶段三：Vue3 页面

完成：9 个运维导航入口、服务器/仓库/部署表单/容器/备份/告警/任务/授权页面；API 客户端与状态逻辑独立，沿用自有 CSS、Vue Router 和 reactive store。补齐基线缺失的 SidebarNav/TopHeader，复用 HeaderNav 通知和原监控入口；修复旧 Dashboard CSV 字符串语法错误。文件：frontend/src/views/OpsPlatformView.vue、ops/*、api/ops.js、components/*、assets/styles/container-ops.css、router/index.js、store/index.js。数据库：无额外变更。测试：Vue3 构建、8 项前端测试通过；既有监控 API 回归通过。剩余：端到端镜像发布与恢复演练。

## 阶段四：自动部署

完成：持久异步任务、幂等键/请求指纹、主机串行/跨主机并行、拉取并核验 Digest、健康切换、完整版本保存、失败保留候选并回退旧容器、显式数据兼容性确认、未知结果中断标记。文件：OpsTaskService、DeploymentOrchestrationService、DeploymentPolicy、OpsAgentGateway、agent.py。数据库：V002 新建 ops_task_event；任务事件与 SSE/查询均可追溯。测试：实际 Docker 拉取、部署、失败升级回退、Java→SSH→Agent→Docker 浏览器发布链路通过。修复：Spring 代理字段直取空指针、打包资源路径与旧构建残留抢占 Vue 入口、终态/审计/告警提交竞态。剩余：最终交付包完整链路复验。

## 阶段五：检测、告警与自动恢复

完成：独立调度/执行线程、启动宽限、维护模式、手动停机双重标记、持续故障窗口、资源阈值、退避/限次/熔断、Docker 原生重启协作、运行但 unhealthy 的独立策略；新部署不使用旧快照误报；离线主机与缺失容器告警；告警规则、等级、确认/恢复追踪；复用通知铃铛聚合新旧事件。文件：HealthDetectionService、AutoRecoveryService、AlertEventService、OpsScheduling、agent.py、前端告警/通知/日志。数据库：规则/告警使用带版本 ops_resource，不改旧告警表。测试：异常退出真实拉起、持续 unhealthy 真实恢复、人工停机抑制和上限熔断通过；跨应用晚到日志丢弃测试通过。剩余：生产环境的实际阈值和通知值班流程须由环境管理员配置，不能在代码中写死业务值。

## 阶段六：备份与恢复

完成：配置/Secret 引用与数据分类、停止全部写入者的卷冷备、PostgreSQL pg_dump 一致性备份、SHA256 校验、文件内容/属主/权限验证、隔离恢复演练、恢复到新目标、周期调度/保留元数据/失败告警；无自动删原数据。文件：BackupRestoreService、agent.py、备份页、test_agent_docker.py。数据库：备份元数据在 ops_resource。测试：真实卷文件恢复并复核权限；真实 PostgreSQL 恢复后查询数据，与原库值一致，原库保留。剩余：按实际业务完成流量切换/数据兼容性审批；冷备不自动停机。

## 阶段七：集成与优化

完成：原有监控路由保留、统一通知入口、角色/服务器/环境/动作授权及撤销、运维账号旧接口隔离与显式恢复授权、执行前重鉴权、日志任务结果按 logs 权限过滤、审计、SSE/断线查询、内部 HTTP 幂等键兼容、标准非 root JAR 镜像/Compose 与构建脚本。ChatChat 同名开发分支仅新增接入说明，不复制运维业务或改生产配置。文件：deploy/container-platform、scripts/build-container-platform.ps1、02-deployment.md；另仓库 docs/container-ops-platform-integration.md。数据库：账号隔离标记使用既有 JSON 文档，不新增旧表字段；迁移脚本在 H2 与隔离 MySQL 8.4 上验证重复执行，无破坏语句。剩余：目标环境 Agent 安装、实际仓库/密钥配置及生产发布需另行批准。

## 阶段八：验收

最终结果和证据集中记录在 04-acceptance.md 及 evidence/。Java 功能测试 139 项、Vue3 单元测试 8 项、代理纯测试 24 项、真实 Docker/PG 场景 2 项、Java 调度器自动恢复/熔断闭环 1 项通过；最终镜像与最终浏览器复验均通过。额外覆盖 UTF-8 字节边界、私库身份端点越界拦截、失败恢复演练容器停机、临时 Secret 文件清理、中文/控制字符日志预算，以及状态变更后的快照新鲜度屏障。失败过程中发现的问题已修复后重新验证，不把此前失败记录宣称成功。生产未执行部署。
