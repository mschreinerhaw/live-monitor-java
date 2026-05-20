-- sms_task.py 测试数据
-- 库：sms
-- 表：sms_task

USE sms;

-- 清理旧测试数据
DELETE FROM sms_task
WHERE biz_key LIKE 'demo_db_only_%'
   OR biz_key LIKE 'demo_fallback_%';

-- 场景A：数据库手机号模式（db_only / db_fallback_config 都可测）
-- 预期：直接使用 mobile 列发送
INSERT INTO sms_task (
    biz_key, mobile, content, send_status, retry_count, max_retry, remark
) VALUES
('demo_db_only_001', '18211092191', '【测试】数据库手机号-单条', 0, 0, 3, 'db mobile single'),
('demo_db_only_002', '18211092191,13121234559,15910660759', '【测试】数据库手机号-多条', 0, 0, 3, 'db mobile multi');

-- 场景B：配置回退模式（仅在 MOBILE_SOURCE_MODE=db_fallback_config 时生效）
-- 预期：mobile 为空时使用程序中的 PROGRAM_MOBILES
INSERT INTO sms_task (
    biz_key, mobile, content, send_status, retry_count, max_retry, remark
) VALUES
('demo_fallback_001', '', '【测试】数据库无手机号-回退程序手机号', 0, 0, 3, 'fallback to program mobiles');

-- 可选场景：非法号码（用于验证失败回写）
INSERT INTO sms_task (
    biz_key, mobile, content, send_status, retry_count, max_retry, remark
) VALUES
('demo_db_only_003', '12345,abc,18211092191', '【测试】含非法手机号', 0, 0, 3, 'invalid mobile check');

-- 查看任务执行结果
-- SELECT id, biz_key, mobile, send_status, retry_count, max_retry, last_result, update_time
-- FROM sms_task
-- WHERE biz_key LIKE 'demo_%'
-- ORDER BY id;
