# 统一监控与客户交付验收（2026-10-25）

当前入口：**http://192.168.195.221:18080/#/ops/delivery**。控制面以独立容器 `live-monitor-production-control-platform-1` 常驻服务器，配置入口 `/opt/live-monitor-production-control/compose.json`，数据库和日志分别持久化到该目录的 data、logs。镜像使用私有 TLS 仓库中的不可变 Digest，restart=unless-stopped，以 UID 10001 运行，无 Docker socket、privileged 或主机网络。

本机 Windows 原先仅监听 127.0.0.1；修改为可配置监听后本机 IP 请求成功，但 Windows 拒绝防火墙管理且服务器到该本机地址的访问失败，因此没有将本机自测作为远端访问通过。随后将控制面冷迁移到授权服务器，并从 Windows 浏览器真实验证服务器 IP、交互登录及所有运维页面。本地调度进程已停止，原数据、稳定密钥和迁移前备份保留；启动工具检测 remote-controller.json 后拒绝直接启动第二个控制器。数据库身份、原任务和授权沿用，生产 Runtime OS 七个业务容器在本次控制面迁移中不重启。

## 按阶段实施结果

| 阶段 | 完成的功能 / 修改文件 | 数据库变更 | 验证 / 剩余事项 |
|---|---|---|---|
| 现状复核 | 定位回环绑定、运维页未合并应用栈、最近部署遗漏栈版本、停机后停止采样、同机多环境登记受限 | 无 | 保留原监控和原认证 |
| 后端 | OpsOverviewService / OpsController 汇总授权范围内的应用、服务、依赖、资源、告警和版本；HostManagementService 允许同机不同环境；HealthDetectionService 继续采样停机栈且禁止其自动恢复 | 无 DDL，复用 ops_resource、ops_task、ops_audit | Java 155 项通过；过期数据不计实时健康，共享依赖去重，主机凭据身份与环境目标分别计数 |
| Vue3 | UnifiedMonitorPanel 合并单容器和应用栈，显示 CPU/内存、故障窗口、重试/退避/熔断和人工停机；总览指标置顶，运行应用优先；DeliveryGuidePanel / delivery.js 提供七步向导，栈告警可单独配置 | 无 | Vue 13 项通过；向导不把保存配置或备份校验当作完整验收 |
| Agent / 联机 | stack_ops.py 采集实际恢复策略、failure_since、next_recovery_at 和原生策略；支持在批准包中明确禁用 unhealthy 恢复 | 无 | Agent 40 项通过；真实隔离 Compose 验收 1 项通过，无跳过 |
| 自动闭环 | 由平台异步发布隔离应用，后台 system 身份发现故障并恢复；再次故障达到上限后告警；人工停机 70 秒后仍保持停止并继续采样 | 沿用任务、状态、告警记录 | 见 platform-auto-recovery.json；没有对生产业务注入故障 |
| 交付打包 | release.xml 附带 Agent、配置样例、Compose、安装/防火墙工具及客户手册；构建工具按原生命令退出码判断结果；CLI 验证持久化控制面地址 | 无 | JAR / zip / tar.gz 构建成功，交付资源已检查 |
| 服务器部署 | 独立镜像与持久数据，原加密身份冷迁移，停用本机调度；浏览器从另一台主机通过服务器 IP 登录 | 原数据复制到新专用目录，源数据保留 | 16 项浏览器检查通过，无页面脚本错误；后续后台采集与版本、凭据可读性已核验 |

## 实际恢复证据

隔离项目 `lm-stack-acceptance-67d4f91b` 与生产使用不同批准包、环境、状态和数据目录，主机策略仅批准该测试项目。测试结果包括：

- 平台发布任务：`58630b79-e312-495d-8648-cef6cf8e9dc8`。
- 无人工恢复请求，后台自动恢复任务：`c8af3471-9494-4b62-9b34-6e42cbd7bc4a`，成功。
- 达到恢复上限后告警：`4fa243ee-580e-3184-a9f4-75db576615fd`。
- 人工停机任务：`73fa2a03-d53e-4a28-8021-a5af61bb3f9a`。后续 system 继续采样，服务保持 exited / manual_stop，未触发自动拉起。

验收后测试应用保留停止状态作为追踪记录。实际物理服务器为一台，登记了生产与隔离验收两个环境目标；不会把两个环境宣传为两台物理服务器。

## 交付与证据

操作顺序与验收边界见 [08-customer-delivery.md](08-customer-delivery.md)。本地登录文件 `target/runtime-os-production-control/login-private.json` 已记录服务器入口，只含账号信息；完整身份、冷备和迁移工具留在忽略目录，不提交 Git。

最终构建、运行镜像、页面和恢复证据见 [delivery-evidence](delivery-evidence/)：summary.json、server-release.json、browser.json、platform-auto-recovery.json、business-preservation.json 和 automatic-monitoring.json。构建源码为 `10b1d34`，镜像和运行 JAR SHA256 记录在证据中。

剩余交付验收：客户自己的镜像与 Agent 白名单、账号/授权、告警处理人员及外部通知渠道需要按现场配置；平台事件记录不代表短信/邮件已送达。客户生产数据库灾难恢复和多台物理服务器验收不由本次单服务器隔离演练替代。Windows 防火墙修改未成功，当前通过服务器入口交付，无需依赖本机端口入站。
