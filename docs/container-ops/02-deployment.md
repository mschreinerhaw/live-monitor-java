# 构建、隔离验收与部署手册

当前本地控制面及 Runtime OS 生产接管、启动与后续发布流程见 [07-runtime-takeover.md](07-runtime-takeover.md)。用户已明确授权并完成七服务实际替换；下文独立验证流程保留作历史与新环境参考。

初次开发阶段仅在本地隔离验收。2026-10-25 用户进一步授权部署到服务器验证：采用独立实例、端口和数据目录，保留现有生产服务。后续替换生产实例仍需明确指定环境与变更范围。

## 构建

沿用 Java 8 源码兼容级别，建议 Java 17 运行；Maven 3.8+、Node 22+、Python 3.11+（仅受控代理）及 Docker Engine。

Windows：`powershell -File scripts/build-container-platform.ps1`。不执行 frontend 的 `npm run deploy`，该旧脚本有远程部署副作用。

Linux：

```sh
cd live-monitor-java/frontend
npm ci --no-audit --no-fund
npm test
node node_modules/@vue/cli-service/bin/vue-cli-service.js build --dest ../target/frontend-dist
cd ..
mvn package -Pcontainer-platform
```

构建生成独立 target/frontend-dist，并由 Maven profile 放入 JAR 的 static/。不会清空 src/main/resources/static，也不会删除旧监控资源。仓库基线错误地跟踪了部分 target 文件，验收后恢复这些生成文件；不得把它们作为新功能源码提交。

将生成 JAR 复制到独立镜像上下文 app.jar，与 deploy/container-platform/Dockerfile、.dockerignore 同目录，再执行：

```sh
docker build --build-arg JAVA_RUNTIME_IMAGE=YOUR_VERIFIED_JAVA_RUNTIME_IMAGE@sha256:YOUR_DIGEST -t YOUR_REGISTRY/live-monitor:YOUR_VERSION .
docker push YOUR_REGISTRY/live-monitor:YOUR_VERSION
docker image inspect YOUR_REGISTRY/live-monitor:YOUR_VERSION --format '{{json .RepoDigests}}'
```

镜像不包含 Docker socket、Agent 密钥、SSH 凭据、数据或备份；以 UID 10001 运行。发布使用仓库 Digest，不使用浮动 latest。compose.yaml 需要 LIVE_MONITOR_IMAGE 和稳定的 LIVE_MONITOR_SECRET_KEY；仅绑定 localhost，经现有 HTTPS 入口提供服务。DEBUG 显式关闭，避免宿主的 DEBUG 环境变量意外开启框架请求日志。

首次启动沿用旧系统的初始账号机制，**在开放网络入口前修改初始管理员密码**，并配置最小权限用户。已有数据库不得重新初始化或覆盖；升级备份既有 H2/MySQL、RocksDB、配置与加密密钥。更换加密密钥将导致既有 SSH 密文无法解密。

## 受控代理安装（仅在批准的目标服务器执行）

1. 独立运维 SSH 账号/密钥，不开放 Docker TCP。代理是固定命令入口，JSON 从 stdin 传入，不能请求任意 shell。
2. 校验后把 agent/agent.py 与 agent/stack_ops.py 安装到 /opt/live-monitor-agent，root 所有、不可由 SSH 账号修改；可使用 scripts/install-agent-release.py 原子替换代码并备份原版本。root 创建 /etc/live-monitor-agent.json（0600）。默认环境保持兼容，新增环境通过显式 profiles 分别绑定状态、备份与批准发布包策略；不允许页面任意创建执行域。
3. state_root、backup_root、secrets_root 创建为 0700，备份放在独立持久磁盘。Secrets 为本地只读文件，Docker 私库凭据为 secrets_root/<credential_ref>/config.json（0600）。不要把这些文件放入 Git、页面或应用镜像。
4. authorized_keys 使用 `restrict,command="sudo -n /usr/bin/python3 /opt/live-monitor-agent/agent.py"`。sudoers 只允许这一条不可修改脚本，禁止参数/任意解释器命令；SSH 转发、PTY、密码登录关闭。也可由已有经批准的主机安全代理执行同一固定入口。
5. 经可信渠道取服务器 SSH 公钥，核对指纹；平台保存完整 `ssh-ed25519 BASE64` 等公钥，运维连接 StrictHostKeyChecking=yes。公钥轮换必须显式重新核验。
6. 在旧“主机维护”中登记加密 SSH 密钥，再到新“服务器管理”引用该主机、绑定环境和分组。不得把生产主机误注册为测试环境。

不要把现有监控身份的密钥改成 forced-command 而破坏原 Shell 采集。复用具备代理执行权限的原监控身份时保留其既有认证与采集能力，运维后端仍只调用固定 Agent 入口；需要强制命令隔离时使用独立运维身份，并独立管理该身份的监控配置。上述 SSH 限制用于专用运维身份，不应直接修改服务器上原账号的全局登录策略。

Agent 的 Docker 权限仅在目标主机，由授权固定代理控制。应用容器禁止 privileged/host 网络/Docker socket 挂载；配置 bind 挂载只读且必须在主机白名单内；命名卷同时核对应用和环境标签。备份 helper 仅挂指定源卷/新目标卷和备份目录，无网络、限制资源，保留 DAC_OVERRIDE/CHOWN/FOWNER 以读取、恢复文件所有权，不能访问 Docker socket。

## 发布与回滚

- 配置仓库白名单、Secret 引用和单应用部署参数；可查询 V2 仓库 catalog/Tags，并解析内容 Digest。公共仓库未开放 catalog 时直接输入 repository 查询。TLS 校验开启，Bearer 身份端点必须在代理允许列表内；分页明确最多 100 项。
- 保存配置 → 发布前校验（拉取、Digest 核验、网络/参数检查）→ 输入操作:资源:环境确认 → 异步任务。健康检查为容器内 argv；进度、日志和结果保存在任务中，并经 SSE/断线查询展示。
- 更新时保留旧容器并切换名字；新容器健康检查失败会恢复旧容器原先运行状态。失败候选同样保留，镜像清理无 force/prune，Docker 会拒绝删除仍被引用的镜像。
- 有状态升级/回滚必须明确确认数据兼容性；平台不自动回退数据库结构。回滚版本保存完整原始 Digest、端口、卷、资源、健康与 Secret 引用。
- 人工停止先持久化 STOPPED 意图，再执行 Docker stop；Agent 也写人工停机标记。维护状态在平台和 Agent 各自持久化。新控制器启动至少 60 秒宽限，避免自身重启告警。
- 进程退出优先由 Docker 原生策略处理；平台只恢复 restart=no 的非零异常退出或显式启用的持续 unhealthy。抖动窗口、指数退避、独立尝试计数和熔断均受限。原生 restart 次数到阈值时关闭该容器原生重启策略并升级告警，防止无限拉起。显式启动/重新发布才重置预算。
- 运行状态变更完成后必须取得晚于该操作的新快照，才能再次决定恢复；恢复退避和 Docker 健康检查 starting 阶段先等待稳定。显式启动/重启重置故障判定窗口，防止旧故障时间造成误报。

## 备份、恢复、保留

- stateless：部署配置/不可变镜像/Secret 引用清单，不能宣称包含数据。
- volume：所有写入该卷的容器必须停止后冷备；校验归档 SHA256 与逐文件内容，拒绝路径穿越、符号/硬链接、设备文件。缺失源卷直接失败，不能创建空卷伪造成功。
- postgres：在运行中数据库通过 pg_dump custom 取得一致性逻辑备份；不复制运行中的 PGDATA 文件。
- 恢复演练：卷恢复到新卷并重新打包对比每个文件内容；PostgreSQL 恢复到无网络验证容器的新数据库并执行查询。通过后才允许正式恢复。
- 正式恢复依然创建新卷/新数据库，返回目标，原卷/原数据库保持不变。应用切换到恢复目标需后续批准发布；备份不等于自动业务流量切换。
- 周期以小时配置，0 为手动；卷冷备不会自动停机，未停止写入会失败并告警。记录保留天数，到期不自动删除原数据或备份，清理由管理员审批执行。配置/密钥管理系统还应另行备份真实 Secret，平台只备份引用。

## 任务与异常恢复

任务：QUEUED → RUNNING → SUCCEEDED/FAILED；控制器中断且超时的 RUNNING 标记 INTERRUPTED，不自动重试未知远端结果。幂等键绑定请求指纹；同一主机任务互斥，不同主机隔离线程执行。SSH 连接 8 秒、输出上限、子命令超时、Agent 总时限 1100 秒；平台超时默认 1200 秒。

Agent 持久化每个 task_id 的请求哈希和结果。遇到 INTERRUPTED/RECONCILE_PREVIOUS_TASK，管理员先核对 Agent state_root/tasks、diagnostics 与 Docker 实际状态，再决定后续操作，禁止直接删除标记强行重跑。审计和持久任务与原监控调度器、工作线程隔离。

日志按任务串行拉取最近最多 100 行，再以 UTF-8 尾部 16,000 字节和 JSON 传输预算限制；超长日志只展示尾部，中文和控制字符不会突破数据库的字节容量。新增运维事件在平台告警中心和通知铃铛展示，原监控外发渠道保留；如需将运维事件转发到企业外部值班通道，应单独完成接入验收。

## 隔离验收

测试容器/数据均在本地 Docker Desktop 或独立测试 daemon；禁止使用生产 Docker context。Python 纯测试：

```sh
docker build -f agent/Dockerfile.test -t live-monitor-agent-test:local agent
docker run --rm --network none --mount type=bind,src=YOUR_REPOSITORY,dst=/test,readonly live-monitor-agent-test:local -m unittest discover -s agent -p test_agent.py -v
```

真实 Docker 用 agent/test_agent_docker.py，需显式 LIVE_MONITOR_AGENT_DOCKER_ACCEPTANCE=1 和已核验的 Python、helper、PostgreSQL Digest。测试运行器与 daemon 共享 /tmp/live-monitor-ops-acceptance，挂测试 daemon socket；不是生产部署方式。测试会保留隔离卷、备份与失败候选供检查，不会清理原数据。

浏览器完整链路：在 localhost:18080 启动使用全新 H2/RocksDB 目录的 JAR，单独启用 agent/test_ssh_entrypoint.sh 测试容器（只绑定 localhost:22222）；复制其 fixture-private.json 到忽略的 target/e2e，运行 `node scripts/test-container-platform-browser.mjs`。该测试拒绝非 localhost 目标。测试入口脚本只用于隔离验收，不得用于生产安装。

## 运维账号与原监控权限边界

旧监控沿用登录后管理的信任模型；新增范围授权不能约束旧接口的 SSH/数据库管理能力。因此通过运维授权页面新建授权时默认 ops_only=true：此类账号只能调用已授权的 /api/ops 接口，以及自身登录/登出接口，不能调用旧监控管理 API。侧边栏与路由同步隐藏旧入口。已有监控用户和不含此标志的历史授权保留原有访问方式；站点管理员保持完整权限。

撤销授权不会清除账号隔离标记。授权用户绑定不可变；恢复旧监控访问必须由管理员明确输入 ALLOW_LEGACY:<用户ID>，并记录审计。多个授权中只要任意一个仍有 ops_only=true，隔离仍有效。不要为只读运维用户关闭此模式；关闭后将恢复旧系统的登录用户权限。
