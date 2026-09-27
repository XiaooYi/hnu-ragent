# 分块合并不变量

本规则约束文档进入向量库前的「块级切分与合并」。实现位置是 `bootstrap` 模块的 `core/chunk/blockaware`：块级切分由各 `BlockChunker` 完成，块间合并由 `StructuredChunkAggregator` 完成。

## 合并边界

1. **捆包边界 = 提纲边界**：两块只有 `outlinePath` 完全相同时才允许合并。跨节合并会让块只能标注其中一节的路径，内容却横跨两节，检索命中后的引用归属与章节说明都会出错。
2. **原子块不参与合并**：`CODE`、`TABLE` 等原子块自己成块——既不并入前一块，也不接收后一块，即使它的体量低于 `minChars`。
3. **短块兜底合并同样受上述约束**：为「不产出召不回、白占 TopK 名额的碎块」而做的短尾合并，必须同时满足 `outlinePath` 相同、非原子块、类型兼容、且合并后不超 `maxChars`。跨节时**宁可保留短块**。
4. **类型兼容只允许两种组合**：
   - 段落 ↔ 段落；
   - 段落组 ↔ 图片（**无描述图片同样允许**，图片资产随块保留）。
   其它组合一律开新块。

## 资产与来源

- 合并后 `assets` 取并集、`sourceBlockIds` 按出现顺序累加、`sectionContext` 去重后拼接，不得因合并丢失任一来源信息。
- 正文为空但带资产的块（无描述图片）**必须保留**：它是该图片在检索结果中的唯一载体，直接丢弃等于把图片从知识库里抹掉。
- 合并后的 `blockType` 归一为 `PARAGRAPH`（含图片的块按段落参与后续检索与展示）。

## 重叠

- 块级**不做**人为重叠：切口只落在提纲/节边界，边界处没有被切断的句子。
- 需要重叠的只有「单节超出 `maxChars` 后的节内切分」，该层由段落切分按 `overlapChars` 负责。

## 配置与验证

- 体量参数：`minChars` / `targetChars` / `maxChars`（见 `BlockChunkConfig`，管理端「分块配置」与 `ChunkingOptionsValidationTest` 共同约束 `0 < minChars <= targetChars <= maxChars`）。
- 验收用例：`bootstrap/src/test/java/com/hnu/ragent/core/chunk/blockaware/StructuredChunkAggregatorTest.java`
  - 同提纲段落合并并累加来源；
  - 不跨提纲/原子边界（`[A:p1] [B:p2] [B:code] [B:p3]` → 4 块）；
  - 有描述与无描述图片都可并入相邻段落并保留资产。

修改合并判据、`minChars` 语义或块级重叠策略时，必须同步本文件、上述测试与 `docs/upstream/features/up-13-chunking-refactor.md`。
