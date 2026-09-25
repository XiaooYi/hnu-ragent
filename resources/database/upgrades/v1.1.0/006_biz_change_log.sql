-- v1.1.0-006 业务数据变更审计日志
-- 新增 t_biz_change_log：记录知识库 / 文档 / 意图节点 / 示例问题 / 关键词映射 / 用户等写操作的操作人、
--   操作类型、变更前后快照与字段级差异（diff 在后端计算，JSON Pointer 路径）
-- 说明：审计日志只增不改；查询按 (biz_type, biz_id)、create_time、operator_id 走索引
-- 幂等：重复执行不会报错

CREATE TABLE IF NOT EXISTS t_biz_change_log (
    id               VARCHAR(20)  NOT NULL PRIMARY KEY,
    biz_type         VARCHAR(64)  NOT NULL,
    biz_id           VARCHAR(64)  NOT NULL,
    operation_type   VARCHAR(32)  NOT NULL,
    action_desc      VARCHAR(512),
    before_snapshot  JSONB,
    after_snapshot   JSONB,
    change_diff      JSONB,
    operator_id      VARCHAR(64),
    operator_name    VARCHAR(128),
    operator_role    VARCHAR(64),
    success          BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message    TEXT,
    class_name       VARCHAR(255),
    method_name      VARCHAR(255),
    ip               VARCHAR(64),
    user_agent       VARCHAR(512),
    create_time      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_biz_change_log_biz ON t_biz_change_log (biz_type, biz_id);
CREATE INDEX IF NOT EXISTS idx_biz_change_log_time ON t_biz_change_log (create_time);
CREATE INDEX IF NOT EXISTS idx_biz_change_log_operator ON t_biz_change_log (operator_id);

COMMENT ON TABLE t_biz_change_log IS '业务数据变更审计日志表';
COMMENT ON COLUMN t_biz_change_log.biz_type IS '业务对象类型';
COMMENT ON COLUMN t_biz_change_log.biz_id IS '业务对象主键';
COMMENT ON COLUMN t_biz_change_log.operation_type IS '操作类型';
COMMENT ON COLUMN t_biz_change_log.action_desc IS '操作描述';
COMMENT ON COLUMN t_biz_change_log.before_snapshot IS '变更前快照';
COMMENT ON COLUMN t_biz_change_log.after_snapshot IS '变更后快照';
COMMENT ON COLUMN t_biz_change_log.change_diff IS '字段级变更差异';
COMMENT ON COLUMN t_biz_change_log.operator_id IS '操作人ID';
COMMENT ON COLUMN t_biz_change_log.operator_name IS '操作人名称';
COMMENT ON COLUMN t_biz_change_log.operator_role IS '操作人角色';
COMMENT ON COLUMN t_biz_change_log.success IS '是否成功';
COMMENT ON COLUMN t_biz_change_log.error_message IS '失败信息';
COMMENT ON COLUMN t_biz_change_log.class_name IS '触发类名';
COMMENT ON COLUMN t_biz_change_log.method_name IS '触发方法名';
COMMENT ON COLUMN t_biz_change_log.ip IS '来源IP';
COMMENT ON COLUMN t_biz_change_log.user_agent IS 'User-Agent';
COMMENT ON COLUMN t_biz_change_log.create_time IS '创建时间';
