-- v1.2.0-002 Agent 长期记忆
-- 新增 t_agent_memory：跨会话保留的用户事实（偏好、背景、约束）
-- 失效语义：事实不物理删除，被新事实取代时写 invalid_at + superseded_by，读路径只取 invalid_at IS NULL，
--   部分索引让失效行不进索引；这样「记错了什么 / 什么时候被改掉」可回溯
-- 幂等：重复执行不会报错

CREATE TABLE IF NOT EXISTS t_agent_memory (
    id            VARCHAR(20)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(20)  NOT NULL,
    content       VARCHAR(500) NOT NULL,
    source_type   VARCHAR(16)  NOT NULL,
    invalid_at    TIMESTAMP,
    superseded_by VARCHAR(20),
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 部分索引：读路径只查 ACTIVE，失效行不进索引
CREATE INDEX IF NOT EXISTS idx_agent_memory_active ON t_agent_memory (user_id) WHERE invalid_at IS NULL;

COMMENT ON TABLE t_agent_memory IS 'Agent 长期记忆事实表';
COMMENT ON COLUMN t_agent_memory.content IS '记忆内容（一句话事实）';
COMMENT ON COLUMN t_agent_memory.source_type IS '来源类型：PREFERENCE / FACT / CONTEXT';
COMMENT ON COLUMN t_agent_memory.invalid_at IS '失效时间；NULL 表示生效中';
COMMENT ON COLUMN t_agent_memory.superseded_by IS '取代该条记忆的新记忆 ID';
