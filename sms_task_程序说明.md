# sms_task.py 程序说明

## 1. 功能概述
- 单表短信任务发送程序（表：`sms.sms_task`）。
- 仅处理 `send_status=0` 的任务。
- 支持短信网关 `TEXT/XML` 返回解析。
- 程序异常时退出码为 `-1`，便于调度器识别失败。

## 2. 两种手机号来源模式
在 `sms_task.py` 顶部配置：

```python
MOBILE_SOURCE_MODE = "db_fallback_config"
PROGRAM_MOBILES = "18211092191"
```

支持两种模式：

1. `db_only`
- 仅使用数据库字段 `mobile`。
- 若任务 `mobile` 为空，则本次任务记为失败（不发送）。

2. `db_fallback_config`
- 优先使用数据库字段 `mobile`。
- 若任务 `mobile` 为空，则回退使用程序配置 `PROGRAM_MOBILES`。

说明：
- `PROGRAM_MOBILES` 支持多个号码，分隔符支持：英文逗号、分号、空格、中文逗号、中文分号。

## 3. 关键配置项
- MySQL：`MYSQL_HOST` `MYSQL_PORT` `MYSQL_USER` `MYSQL_PASSWORD` `MYSQL_DATABASE`
- 表字段：`TABLE_NAME` `CONTENT_COLUMN` `MOBILE_COLUMN` 等
- 网关：`SMS_API_URL` `SMS_USERNAME` `SMS_PASSWORD_IS_MD5` `SMS_PASSWORD_MD5` `SMS_RSTYPE`
- 运行：`SMS_BATCH_SIZE` `SMS_HTTP_TIMEOUT` `MAX_TASKS_PER_RUN`

## 4. 表结构（程序自动创建）
程序启动时自动：
1. 检查库 `sms` 是否存在，不存在则创建。
2. 检查表 `sms_task` 是否存在，不存在则创建。

核心字段：
- `biz_key`：业务唯一键
- `mobile`：手机号（可多号）
- `content`：短信内容
- `send_status`：任务状态
- `retry_count` / `max_retry`：重试控制
- `last_result`：最近发送结果

## 5. 状态码说明
- `0`：待发送
- `1`：发送中
- `2`：部分成功
- `3`：全部成功
- `4`：全部失败

## 6. 运行方式
```bash
python3/bin/python3 sms_task.py
```

退出码：
- `0`：程序执行成功
- `-1`：程序异常失败

## 7. 测试数据
已提供文件：`sms_task_test_data.sql`

导入：
```sql
SOURCE /home/livedata/sms_task_test_data.sql;
```

或复制 SQL 内容直接执行。

## 8. 日志查询
SELECT id, log_time, log_level, logger_name, module, func_name, line_no, message
FROM sms.sms_task_log
ORDER BY id DESC
LIMIT 200;

