-- v1.1.0-003 意图节点支持关联多个知识库 Collection
-- t_intent_node：新增 collection_names（JSONB 列表），collection_name 仅保留用于兼容旧数据
-- 幂等：重复执行不会报错；已有行的新列为 NULL，读侧会回退到 collection_name

ALTER TABLE t_intent_node ADD COLUMN IF NOT EXISTS collection_names JSONB DEFAULT NULL;

COMMENT ON COLUMN t_intent_node.collection_names IS '关联的 Collection 名称列表；为空时回退 collection_name';

-- 数据回填：把旧单值字段搬进新列，便于后续只读新列的实现平滑切换
UPDATE t_intent_node
SET collection_names = to_jsonb(ARRAY[collection_name])
WHERE collection_names IS NULL
  AND collection_name IS NOT NULL
  AND btrim(collection_name) <> '';
