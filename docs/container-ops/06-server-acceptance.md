# 服务器部署与验证记录

日期：2026-10-25。用户已授权将平台部署到服务器验证。产品定位见 [05-product-positioning.md](05-product-positioning.md)。本次新增独立平台，不替换已有监控系统或 ChatChat 生产服务。

## 部署结果

| 项目 | 实际结果 |
|---|---|
| 授权服务器 | 192.168.195.221，SSH 5221，Linux x86_64 / Docker 26.1.3 / Python 3.11.13 |
| 平台容器 | live-monitor-container-platform-validation，健康检查 healthy |
| 访问入口 | 服务器 127.0.0.1:18088；本机 SSH 隧道 127.0.0.1:18588 |
| 安装与数据目录 | /opt/live-monitor-container-platform-validation；data、logs、backups、agent-state 独立持久化 |
| 应用代码版本 | 3a49803aa0b5866da51f1d59d5ef4dbfb06f4ca7 |
| JAR SHA256 | 74ee3e845fd5d1b5b551a50a44650de6f87ae58354983aac78f81592862f37da |
| 平台运行镜像 | localhost:18443/live-monitor/platform@sha256:d2847273a85458a1b53f25b0659d4402850819d9cfd4d03dfc62ef6342fe082b |
| 测试应用镜像 | localhost:18443/live-monitor/validation-python@sha256:1572e703d663cdd4297c7011812196381b9cc52a14a20afd8f9ab59e15732bb3 |
| 资源和权限 | 平台 UID 10001，CPU 1 / 内存 1 GiB，cap-drop ALL，无 Docker socket 挂载 |
| Agent | 专用 lm-ops-validation 身份、受限公钥固定命令、精确 sudoers、严格核对服务器公钥 |
| 账号 | 初始管理员密码已更换为随机值；旧密码登录返回 401；凭据仅存放于私有文件 |

Docker Hub 在服务器上连接失败，因此使用离线校验归档和本机 TLS 认证私库完成完整链路。仓库只监听 127.0.0.1:18443，使用独立证书、Docker 端点信任及 Agent 专用 TLS 信任文件，不关闭证书校验，不修改现有 Docker daemon 配置或开放 TCP API。

首次生成证书时发现服务器 OpenSSL 默认扩展与命令参数重复，导致仓库拒绝启动。已改为显式最小证书配置，修复后完成推送、Digest 校验和 Agent 私库查询；未将失败尝试记为通过。

## 实际验证

服务器业务闭环 **13 项全部通过**，原始证据：[server-evidence/acceptance.json](server-evidence/acceptance.json)。

1. 原监控 API 可访问；原独立监控服务 8000 的健康接口继续返回 ok。
2. 主机公钥固定校验及受限 SSH Agent 调用成功。
3. TLS/认证仓库 catalog、Tag 解析和内容 Digest 查询成功。
4. 同一平台实际发布两套独立应用，配置、容器、版本和任务各自归属明确。
5. 发布第二版本后，受控回滚到原版本，核对运行参数恢复。
6. 注入失败候选版本，发布任务显示失败原因，原健康容器恢复运行。
7. 写入真实卷文件，停止应用冷备、恢复演练、正式恢复到新卷；原卷内容保留且一致。
8. 对标记为 validation 的测试应用注入持续 unhealthy，后台 system 任务自动恢复。
9. 人工停止后跨采集周期观察，容器保持停止，STOPPED 意图保留。
10. 对专属测试容器注入异常退出，后台 system 任务自动拉起。
11. 再次异常退出达到重启上限，产生 CIRCUIT_OPEN，停止重试；最终将此应用主动停止。
12. 范围只读账号访问旧主机接口、执行未授权重启均返回 403，审计有拒绝记录。
13. 部署前后原有 19 个容器的镜像、启动时间和重启次数均未改变；未操作其业务负载。

远程浏览器 **11 项检查全部通过**：九个运维页面、390px 窄屏、旧默认密码拒绝；1440px 桌面及窄屏无横向溢出、无页面错误。[浏览器证据](server-evidence/browser.json)。

新增部署脚本安全测试 **4 项通过**：已有数据拒绝覆盖、上传目录保留、恢复安装必须有所有权标记、标记必须匹配原版本与路径。原 Java 139 / Vue3 8 / Agent 24 项验收结果继续适用于未变更的应用代码；本次新增部署与验证脚本，没有重新改动 JAR。

## 访问和运行

本机当前已建立隧道，可访问 **http://127.0.0.1:18588/**。其他机器可执行：

```sh
ssh -p 5221 -N -L 18588:127.0.0.1:18088 root@192.168.195.221
```

账号 admin。随机密码保存在服务器 `/opt/live-monitor-container-platform-validation/config/browser-auth-private.json`，本机私有副本为仓库忽略目录 `target/server-validation/browser-auth-private.json`，已限制本机文件 ACL。密码不写入本报告或 Git。平台、认证仓库及一个健康测试应用保留运行；恢复熔断测试应用已主动停止。保留卷、恢复目标、备份和失败候选用于审查，不自动删除。

通过正式 HTTPS 入口开放访问、迁移旧监控数据或接管生产应用属于后续明确变更，当前独立实例并未执行这些操作。

如需撤回验证入口，仅停止 `live-monitor-container-platform-validation` 与 `live-monitor-validation-registry` 两个新增服务；不要停止原服务，不使用 prune，也不删除 data、backups 或恢复卷。测试应用是否继续运行由平台显式停机操作决定，不能通过删除平台实例推断业务停机意图。

## 文件、数据库和剩余验收

新增脚本：`scripts/deploy-server-validation.py`、`verify-server-validation.py`、`verify-server-platform-browser.mjs`、`test-server-deployment-safety.py`。部署脚本不接受未知已有目录，部分安装只能使用绑定来源和路径的所有权标记恢复；Agent 已存在时拒绝覆盖。部署过程私有诊断保留在服务器，不导出凭据。

数据库：在新 data 目录初始化 H2 及 ops V001/V002，保留现有生产数据库、配置和密钥。配置备份、卷备份和 Secret 引用仍按各自一致性策略管理，镜像不等于应用完整备份。

当前实机验收物理服务器数量为 **1**。多服务器并发、离线隔离、环境范围授权已有本地测试证据；第二台物理服务器及第二实际环境的部署验收，需要补充授权目标后执行。没有将同机多个应用称为多台服务器。长期容量、磁盘耗尽、主机级灾难恢复和业务数据库切换仍按目标环境演练。

本次未向主分支合并，所有新增记录和脚本位于 feature/container-ops-platform。
