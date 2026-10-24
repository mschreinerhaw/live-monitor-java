# 阶段一：现状审计与增量方案

审计日期：2026-10-24。修改业务代码前记录。

## Git 与范围

| 仓库 | 地址 | 原分支/提交 | 已核实基线 | 开发分支 |
|---|---|---|---|---|
| live-monitor-java | github.com/mschreinerhaw/live-monitor-java | main / f78dfe8 | origin/feature/vue3-frontend / 9222560 | feature/container-ops-platform |
| Langchain4j-Chatchat | github.com/mschreinerhaw/Langchain4j-Chatchat | main / 86d496089 | main 已是 Vue3，chatchat-api/web-app/package.json 与 createApp 入口 | feature/container-ops-platform |

两个仓库已执行 fetch --all --prune，切换前工作区均干净，无丢弃、覆盖、强推。监控仓库先建立跟踪 feature/vue3-frontend，再创建开发分支。ChatChat 不存在需要替换的监控前端，保留现有应用，仅在独立开发分支提供平台集成说明，不复制平台业务。

## 可复用模块

- 前端 live-monitor-java/frontend：Vue 3.4、Vue Router 4 哈希路由、Vue CLI 5/Webpack、ECharts 6；reactive store；自有 CSS 组件体系，无 Element/Pinia。App.vue 实际使用 SidebarNav、TopHeader。保留 dashboard、hosts、services、data-send、alerts、admin 全部路由。
- 后端 live-monitor-java：Java 8 / Spring Boot 2.7.18，Spring MVC，MyBatis，JdbcTemplate。现有 /api/services、/api/hosts、/api/dashboard、/api/alerts 与告警渠道/分组、规则解析器、数据库查询、数据推送均保持不变。
- 认证：AuthInterceptor 校验 HttpSession.LOGIN_USER；管理账号权限通过服务校验。EmbedToken 会建立嵌入会话，因此新增高风险运维必须明确拒绝嵌入身份，不能把嵌入页面当管理员授权。
- 凭据：CryptoService 加密 HostConfig SSH 密码/私钥，主机从 HostMapper 获取；复用密文，不返回给运维前端。原 SshService 关闭 host-key checking，新增运维不能沿用其不安全配置，使用独立严格主机密钥校验的代理适配器。
- 监控：MonitorScheduler 与隔离的 monitorCheckExecutor；SSH 主机资源/进程、Redis、ZooKeeper、Web/端口、数据库检测；RocksDB 历史、Lucene 搜索；AlertService 状态与 AlertDeliveryService 通道。新增运维线程池独立，单机离线不阻塞原监控。
- 数据：默认 H2，支持 MySQL/MariaDB；schema-h2.sql/schema-mysql.sql 与 SchemaMigrationService/迁移标记；SQLite→H2/RocksDB、H2→MySQL 既有迁移不修改。新增版本化 ops_* 表，不删除原表或数据。
- 未发现既有 Docker 编排/镜像部署、容器恢复模块，也无 WebSocket/SSE 通道。新增可鉴权任务 SSE，断线后可按持久任务查询恢复。
- 测试：7 个既有 Java 测试类，Spring Boot Test/JUnit；前端目前无测试脚本。Maven 打包可执行 JAR 与 assembly，前端 build 直接输出后端 static（验收构建应改用独立 outputDir 防止覆盖旧文件）。

## 新增模块与边界

独立 com.live.monitor.ops，API /api/ops，沿用会话认证并新增用户/主机/环境/操作授权。角色 viewer/operator/deployer/recovery/admin 及授权范围由服务端控制。管理员可管理授权，非管理员默认无运维权。写操作要求同源请求及明确确认；生产部署、恢复、清理不能靠隐藏按钮授权。

受控代理通过 SSH 加密通道调用固定 Python 标准库代理，JSON 从 stdin 传递，禁止接受任意 shell；固定 SSH host key、连接/执行超时、输出上限。Docker 只走主机本地 CLI/Unix socket，不开放 TCP。可配置 forced-command SSH key，代理不接受 SSH 原始命令。平台不安装或改动生产 Agent。

主机、仓库、应用/不可变部署版本、任务、备份、告警、授权与审计持久化；任务异步、幂等键和请求指纹、资源互斥、重启后中断状态显式保留，禁止误报成功。配置以 Secret 引用为主；Agent 本地只读 Secret 目录解析，平台不收集 Secret 值。

恢复闭环：维护/人工停机优先；Docker restart policy 处理进程退出，平台处理持续 unhealthy（显式启用）或原生策略 no 的异常退出，退避/阈值/熔断/恢复事件。平台启动宽限期与离线状态不产生容器故障洪泛。

部署：先拉取并核验 digest，检查端口/挂载/资源与健康策略，发布确认，保存旧容器与版本，受控切换；失败恢复原容器，禁止自动删除旧数据。数据不兼容时拒绝自动回滚。

备份：部署配置、冷备命名卷、PostgreSQL 逻辑备份分别建模；校验哈希、恢复到新目标做验证，保留原卷/原数据库。禁止对运行中数据库直接打包文件宣称一致性备份。保留周期仅标记到期，未经明确清理授权不自动删除。

## 阶段一结果

完成：仓库/代码分支核实、干净工作区保护、审计与方案。文件：本文件。数据库变更：无。测试：代码结构核实完成，功能测试待后续阶段。剩余：实现、自动化验收与隔离运行环境验证；不对生产部署。

参考：Docker 官方 restart policy 说明 https://docs.docker.com/engine/containers/start-containers-automatically/ ，卷备份/恢复说明 https://docs.docker.com/engine/storage/volumes/ 。
