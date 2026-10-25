# Runtime OS 一键交付安装

目标是新客户环境的完整安装。运维界面仍只有镜像管理、容器管理、基础监控；在镜像管理点击“安装 Runtime OS”，依次上传正式安装包、确认必要配置、检查并授权、查看安装结果。后台执行镜像导入、依赖启动、数据库初始化、应用健康检查与监控登记。不从镜像名称推测依赖，不执行上传包提供的主机 Shell 命令。

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
python3 install-agent-release.py --release /release/verified-agent --target /opt/live-monitor-agent \
  --installer-product runtime-os --trust-public-key /release/runtime-os-release-20261025.pub \
  --installer-capability docker_socket --installer-capability host_identity \
  --installer-max-cpus 4 --installer-max-memory-mb 4096
```

Agent 清单应包含 agent.py、stack_ops.py、installer.py 及其 SHA256。公钥安装到 root 管理的信任目录。安装资源额度独立于原单容器操作额度，不为了安装大服务放宽普通容器策略。标准包需要约 16 GiB 主机内存、8 核及足够镜像、临时文件和数据空间；安装前还校验当前可用内存、磁盘、版本、地址和端口。主机发布接口由 Agent 既有 `publish_address` 决定，平台不自动修改防火墙。

## 执行与数据保护

- 大包流式传送、落盘和校验。HTTP 安装包上限默认 32 GiB，普通单镜像接口仍为 4 GiB；可以通过配置调整。归档禁止绝对路径、路径逃逸、链接与未声明文件。
- 主机验证签名、内容摘要、产品及能力授权，再生成配置和检查计划。资源、端口、可用网络地址均来自实际主机；检查不启动容器。
- 密码、内部认证与共享密钥按安装独立生成；内部认证使用 AES-GCM。任务请求与私有资源中的参数使用平台既有密钥加密，公开 API、安装结果和诊断下载均不返回参数密文的解密值。
- 用户明确确认后使用有效检查计划安装，执行前重新检查主机条件。签名 Compose 仅允许声明的镜像、隔离网络、有限资源以及新安装目录内的挂载；访问 Docker 和本机授权标识还需主机明确批准。
- PostgreSQL 等依赖完成自身初始化后才启动依赖它们的应用，健康检查不会把 Docker 的 starting 状态当作成功。所有组件就绪后才登记已有应用栈、版本和快照，沿用原后台采集、人工停机、退避和熔断恢复逻辑。
- 任务有幂等键；同一请求重发不会重新部署。页面关闭后保留任务引用，可重新打开向导查询结果。未知结果不会盲目重试；已完成远端安装可按同一计划恢复监控登记。
- 当前是新装流程，不接管已有项目，也不自动覆盖旧生产数据库。新安装失败时尝试停止本次项目，保留数据及失败组件诊断；停止失败明确要求人工核对，不宣称回滚数据库成功。

## 验证记录

核心测试、真实发行包环境预检已完成。完整浏览器安装与服务器发布结果将在实际执行后记录于本节，未完成前不宣称整套安装验收成功。
