-- v1.1.0-002 会话消息思考内容与耗时字段升级
-- t_message 表：新增深度思考内容及耗时字段

ALTER TABLE t_message ADD COLUMN thinking_content TEXT DEFAULT NULL;
ALTER TABLE t_message ADD COLUMN thinking_duration INT DEFAULT NULL;
