-- v1.2.0-001 Agent 会话与消息
-- 新增：
--   t_agent_conversation  Agent 会话列表（与 RAG 版 t_conversation 分立，二者的消息语义不同：
--                         Agent 消息带工具调用块与运行状态，不能与 RAG 消息混存）
--   t_agent_message       Agent 消息（含工具进度块 blocks、结束状态 message_status、耗时 duration_ms）
-- 幂等：重复执行不会报错

CREATE TABLE IF NOT EXISTS t_agent_conversation (
    id              VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(20) NOT NULL,
    user_id         VARCHAR(20) NOT NULL,
    title           VARCHAR(128) NOT NULL,
    last_time       TIMESTAMP,
    create_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT  DEFAULT 0
);

-- 部分唯一索引：逻辑删的旧行不再占用唯一键，否则删除后同 ID 重开会话必撞约束
CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_conversation_user
    ON t_agent_conversation (conversation_id, user_id) WHERE deleted = 0;
CREATE INDEX IF NOT EXISTS idx_agent_conv_user_time ON t_agent_conversation (user_id, last_time);

COMMENT ON TABLE t_agent_conversation IS 'Agent 会话列表';

CREATE TABLE IF NOT EXISTS t_agent_message (
    id                  VARCHAR(20) NOT NULL PRIMARY KEY,
    conversation_id     VARCHAR(20) NOT NULL,
    user_id             VARCHAR(20) NOT NULL,
    role                VARCHAR(16) NOT NULL,
    content             TEXT,
    blocks              JSONB,
    reply_to_message_id VARCHAR(20),
    message_status      VARCHAR(32) NOT NULL DEFAULT 'NORMAL',
    duration_ms         BIGINT,
    create_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    update_time         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT  DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_agent_msg_conv ON t_agent_message (conversation_id, user_id, create_time);

COMMENT ON TABLE t_agent_message IS 'Agent 消息记录';
COMMENT ON COLUMN t_agent_message.blocks IS '消息块（工具进度等）JSON 数组';
COMMENT ON COLUMN t_agent_message.message_status IS '消息结束状态：NORMAL / INTERRUPTED / FAILED';
COMMENT ON COLUMN t_agent_message.duration_ms IS '助手消息耗时（毫秒）';
