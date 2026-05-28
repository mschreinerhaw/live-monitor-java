

# Live-monitor设计文档

**HTML+JS 做监控页面前端；后端服务负责探测 Redis / ZooKeeper / Web / 告警 / 写 SQLite。**

原因很简单：浏览器 JS 不能直接连 Redis、ZooKeeper，也不适合做定时监控和短信邮件告警。

建议架构：

```text
监控页面 HTML/JS
        ↓ 调接口
后端服务 Python / Node.js / Java
        ↓
定时探测任务
  ├─ Redis 集群 ping / info
  ├─ ZooKeeper ruok / srvr / stat
  ├─ Web HTTP GET
        ↓
SQLite 存状态、历史记录、告警记录
        ↓
短信 / 邮件告警
```

页面功能可以这样设计：

```text
服务管理
- 添加服务
- 服务类型：Redis / ZooKeeper / Web应用
- 服务名称
- 地址
- 端口
- URL
- 集群分组
- 检测间隔
- 告警方式：短信 / 邮件

监控首页
- 服务总数
- 正常数量
- 异常数量
- 最近告警
- 服务状态列表

服务详情
- 当前状态
- 最近响应时间
- 最近检测时间
- 历史状态趋势
- 告警记录
```

SQLite 表可以这样设计：

```sql
-- 服务配置表
CREATE TABLE monitor_service (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_name TEXT NOT NULL,
    service_type TEXT NOT NULL, -- redis / zookeeper / web
    cluster_name TEXT,
    host TEXT,
    port INTEGER,
    url TEXT,
    check_interval INTEGER DEFAULT 60,
    alert_email TEXT,
    alert_mobile TEXT,
    enabled INTEGER DEFAULT 1,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 监控结果表
CREATE TABLE monitor_result (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id INTEGER NOT NULL,
    status TEXT NOT NULL, -- UP / DOWN / UNKNOWN
    response_time_ms INTEGER,
    message TEXT,
    checked_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 告警记录表
CREATE TABLE alert_record (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    service_id INTEGER NOT NULL,
    alert_type TEXT, -- sms / email
    alert_content TEXT,
    alert_status TEXT, -- success / failed
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

后端检测逻辑：

```text
Web应用：
HTTP GET URL
状态码 200/302 算正常
超时、500、连接失败算异常

Redis：
连接 host:port
执行 PING
返回 PONG 算正常

ZooKeeper：
连接 host:port
发送 ruok
返回 imok 算正常

告警：
本次 DOWN，且上次是 UP，才发送告警
避免每分钟重复轰炸
```

技术选型建议：

```text
前端：HTML + JS + Bootstrap / Element Plus
后端：Python FastAPI 更快
数据库：SQLite
定时任务：APScheduler
邮件：SMTP
短信：调用短信平台 HTTP API
图表：ECharts
```

结论：
**这个方案完全可行。**
但定位应该是一个**轻量级内部监控系统**，不是替代 Prometheus / Zabbix。你这个需求用 SQLite + Python 后端 + HTML 页面很合适。

live-monitor/
│
├─ backend/                 # 后端服务
│   ├─ app/
│   │   ├─ main.py          # FastAPI 启动入口
│   │   ├─ config.py        # 配置（数据库、告警）
│   │   ├─ models.py        # 数据库模型（SQLite）
│   │   ├─ schemas.py       # Pydantic 数据验证
│   │   ├─ services/        # 各类服务检测逻辑
│   │   │   ├─ redis_monitor.py
│   │   │   ├─ zookeeper_monitor.py
│   │   │   ├─ web_monitor.py
│   │   └─ utils/           # 工具函数（告警、日志）
│   ├─ database.py          # SQLite 连接和初始化
│   ├─ requirements.txt     # Python依赖
│
├─ frontend/                # 前端页面
│   ├─ index.html           # 监控主页
│   ├─ service.html         # 服务详情页
│   ├─ add_service.html     # 添加服务页
│   ├─ css/
│   │   └─ style.css
│   ├─ js/
│   │   ├─ main.js
│   │   ├─ charts.js        # ECharts 图表逻辑
│   │   └─ api.js           # 与后端交互
│   └─ assets/              # 图片、图标
│
├─ scripts/                 # 运行脚本
│   ├─ run_backend.sh
│   ├─ run_frontend.sh
│
├─ logs/                    # 日志
│
├─ tests/                   # 单元测试
│   ├─ test_redis.py
│   ├─ test_web.py
│
├─ docker/                  # Docker 配置
│   ├─ Dockerfile.backend
│   ├─ Dockerfile.frontend
│   └─ docker-compose.yml
│
└─ README.md

UI设计效果

![image-20260528131621496](D:\TyporaImages\image-20260528131621496.png)