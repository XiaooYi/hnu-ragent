-- v1.1.0-004 会话消息支持回答来源
-- t_message：新增 sources（JSONB 列表，文档级来源：序号/文档ID/文档名/类型/链接/摘录）
-- 幂等：重复执行不会报错；历史消息该列为 NULL，读侧降级为空列表

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS sources JSONB DEFAULT NULL;

COMMENT ON COLUMN t_message.sources IS '回答来源（文档级列表），仅 assistant 消息可能有';
