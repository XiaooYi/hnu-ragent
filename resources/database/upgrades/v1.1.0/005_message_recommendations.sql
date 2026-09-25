-- v1.1.0-005 会话消息支持推荐追问与消息结束状态
-- t_message：新增
--   reply_to_message_id    助手消息回答的用户消息 ID（生成推荐追问时定位原问题）
--   grounding_chunks       本轮检索证据片段（jsonb，仅用于推荐生成）
--   recommended_questions  已生成的推荐追问（jsonb；NULL=未生成，[]=已生成但无合适追问）
--   message_status         消息结束状态：NORMAL / INTERRUPTED / REJECTED
-- 幂等：重复执行不会报错；历史消息新列为 NULL，读侧按「未生成 / 正常」处理

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS reply_to_message_id VARCHAR(20) DEFAULT NULL;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS grounding_chunks JSONB DEFAULT NULL;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS recommended_questions JSONB DEFAULT NULL;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS message_status VARCHAR(16) DEFAULT NULL;

COMMENT ON COLUMN t_message.reply_to_message_id IS '助手消息回答的用户消息 ID';
COMMENT ON COLUMN t_message.grounding_chunks IS '推荐追问 grounding 片段（jsonb）';
COMMENT ON COLUMN t_message.recommended_questions IS '已生成推荐追问；NULL=未生成，[]=无合适追问';
COMMENT ON COLUMN t_message.message_status IS '消息结束状态：NORMAL / INTERRUPTED / REJECTED';
