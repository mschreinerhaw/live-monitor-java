# Live Monitor

轻量级内部监控系统，用 SQLite 保存服务配置、检测结果和告警记录，后端负责探测 Web / Redis / ZooKeeper，前端只通过 API 展示和操作。

## 功能

- 服务管理：添加 Web、Redis、ZooKeeper 服务，配置集群、检测间隔、超时、Redis 认证、ZooKeeper 检测方式和告警组绑定，并支持测试连接。
- 监控首页：服务总数、正常/异常数量、平均响应、自动刷新状态、最近告警/检测动态、服务状态列表。
- 服务详情：当前状态、最近响应时间、最近检测时间、历史趋势、检测历史和告警记录。
- 告警中心：维护可复用的告警组，告警组组合告警策略、通知渠道和接收人，服务新增或编辑时选择绑定。
- 检测逻辑：Web HTTP 方法/状态码/关键字校验，Redis AUTH + PING/集群状态，ZooKeeper 四字命令或 TCP 端口连通检测。
- 告警策略：内置 DOWN 连续 3 次、响应时间超过 3 秒、服务恢复三类策略，告警组可自由组合。
- 前端资源：Lucide 和 ECharts 已放在 `frontend/vendor`，页面不依赖 CDN。

## 本地启动

```bash
cd live-monitor/backend
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

启动后访问：

```text
http://127.0.0.1:8000/
http://127.0.0.1:8000/dashboard
http://127.0.0.1:8000/services/new
http://127.0.0.1:8000/alerts/settings
```

Windows 也可以直接运行：

```bat
live-monitor\scripts\run_backend.bat
```

## 告警中心

默认没有配置真实 SMTP 或短信平台时，系统只会写入告警记录。需要真实发送时配置环境变量：

```text
MONITOR_SMTP_HOST
MONITOR_SMTP_PORT
MONITOR_SMTP_USER
MONITOR_SMTP_PASSWORD
MONITOR_SMTP_FROM
MONITOR_SMTP_USE_TLS
MONITOR_SMS_API_URL
MONITOR_SMS_API_TOKEN
MONITOR_SMS_USERNAME
MONITOR_SMS_PASSWORD
MONITOR_SMS_PASSWORD_IS_MD5
MONITOR_SMS_PASSWORD_MD5
MONITOR_SMS_RSTYPE
MONITOR_SMS_EXT_CODE
```

也可以在告警设置页面维护通知渠道，再把策略和渠道组合成告警组，最后到服务里选择需要绑定的告警组。

## API

- `GET /api/dashboard`
- `GET /api/services`
- `POST /api/services`
- `POST /api/services/test`
- `GET /api/services/{id}`
- `PUT /api/services/{id}`
- `GET /api/alert-policies`
- `GET /api/alert-channels`
- `POST /api/alert-channels`
- `PUT /api/alert-channels/{id}`
- `DELETE /api/alert-channels/{id}`
- `GET /api/alert-groups`
- `POST /api/alert-groups`
- `PUT /api/alert-groups/{id}`
- `DELETE /api/alert-groups/{id}`
- `PUT /api/services/{id}/alert-group`
- `GET /api/alert-configs`
- `POST /api/alert-configs`
- `PUT /api/alert-configs/{id}`
- `DELETE /api/alert-configs/{id}`
- `PUT /api/services/{id}/alert-config`
- `DELETE /api/services/{id}`
- `POST /api/services/{id}/check`
- `GET /api/services/{id}/results`
- `GET /api/services/{id}/alerts`

## 测试

```bash
python -m unittest discover live-monitor/tests
```
